/*
 * Copyright (c) 2021, The Linux Foundation. All rights reserved.

 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.

 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.services.telephony;

import android.os.Bundle;
import android.telecom.Conference;
import android.telecom.Connection;
import android.telephony.DisconnectCause;
import android.telecom.StatusHints;

import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.imsphone.ImsPhone;
import com.android.internal.telephony.imsphone.ImsPhone.ImsDialArgs.DeferDial;
import com.android.internal.telephony.imsphone.ImsPhoneConnection;
import com.android.ims.internal.ConferenceParticipant;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Objects;

/* Handles dialing an outgoing call when there is an ACTIVE call on the other sub */
public class AcrossSubDialHandler extends AcrossSubHandlerBase {
    private TelephonyConnection mConnToDial = null;
    private TelephonyConnectionService mConnectionService;
    private Phone mPhone;
    private int mVideoState;
    private Bundle mExtras;
    String[] mParticipants;
    boolean mIsConference = false;
    private com.android.internal.telephony.Connection mOriginalConnection = null;
    // Determines if across sub dial is invoked in DSDS transition mode. True means calls on the
    // SUB with the ACTIVE call are disconnected before dial is sent out. False means ACTIVE call
    // on the other SUB is HELD before dial is sent out.
    private boolean mIsDsdsTransition = false;
    private List<Connection> mConnectionList = new CopyOnWriteArrayList<>();
    private List<Conference> mConferenceList = new CopyOnWriteArrayList<>();

    private final TelephonyConferenceBase.TelephonyConferenceListener
        mTelephonyConferenceListener =
            new TelephonyConferenceBase.TelephonyConferenceListener() {
                @Override
                public void onDestroyed(Conference conference) {
                    Log.d(this, "AcrossSubDialHandler: onDisconnected conference = " + conference);
                    if (!(conference instanceof ImsConference)) return;
                    TelephonyConferenceBase conf = (TelephonyConferenceBase) conference;
                    conf.removeTelephonyConferenceListener(mTelephonyConferenceListener);
                    removeConference(conference);
                    try {
                        maybeDial();
                    } catch (CallStateException e) {
                        handleDialException(e, "outgoing call failed");
                    }
                }
            };

    public AcrossSubDialHandler(TelephonyConnection connToHold, TelephonyConnection connToDial,
                TelephonyConnectionService connectionService, Phone phone, int videoState,
                boolean isDsdsTransition, Bundle extras) {
        this(connToHold, connToDial, connectionService, phone, videoState, isDsdsTransition);
        mExtras = extras;
    }

    public AcrossSubDialHandler(TelephonyConnection connToHold, TelephonyConnection connToDial,
                TelephonyConnectionService connectionService, Phone phone, int videoState,
                boolean isDsdsTransition, String[] participants) {
        this(connToHold, connToDial, connectionService, phone, videoState, isDsdsTransition);
        mParticipants = participants;
        mIsConference = true;
    }

    private AcrossSubDialHandler(TelephonyConnection connToHold, TelephonyConnection connToDial,
                               TelephonyConnectionService connectionService, Phone phone,
                               int videoState, boolean isDsdsTransition) {
        mConnToDial = connToDial;
        mConnToHold = connToHold;
        mConnectionService = connectionService;
        mPhone = phone;
        mVideoState = videoState;
        mIsDsdsTransition = isDsdsTransition;
        if (mConnToHold != null) {
            mConnToHold.addTelephonyConnectionListener(this);
        }
    }

    // Holds ACTIVE call and invokes dial to get a pending connection
    @Override
    public com.android.internal.telephony.Connection dial() throws CallStateException {
        Log.d(this, "AcrossSubDialHandler: dial isDsdsTransition = " + mIsDsdsTransition);
        if (mIsDsdsTransition) {
            // DSDA Transition use case
            disconnectAndDial();
            return mOriginalConnection;
        }
        // DeferDial is enabled which means that ImsPhoneCallTracker will only return a pending
        // connection without placing the call. Call will be placed after hold completes and
        // DeferDial is disabled
        try {
            mOriginalConnection = (mIsConference ? startConferenceInternal(DeferDial.ENABLE) :
                    dialInternal(DeferDial.ENABLE));
            if (mOriginalConnection == null) {
                // Tracker was able to process MMI code. Do not hold active call
                onCompleted(true);
            } else {
                holdInternal();
            }
            return mOriginalConnection;
        } catch (CallStateException e) {
            if (e.getError() == CallStateException.ERROR_HOLD_ACTIVE_CALL_ON_OTHER_SUB) {
                // This error means that the tracker can process the MMI code only after the
                // active call on the other sub is held
                holdInternal();
                return null;
            }
            onCompleted(false);
            throw e;
        }
    }

    private void holdInternal() {
        if (mConnToHold.getState() == Connection.STATE_ACTIVE) {
            mConnToHold.onHold();
        }
    }

    private void onCompleted(boolean success) {
        cleanup();
        notifyOnCompleted(success);
    }

    private void cleanup() {
        if (mConnToHold != null) {
            mConnToHold.removeTelephonyConnectionListener(this);
            mConnToHold = null;
        }
        mConnToDial = null;
    }

    private com.android.internal.telephony.Connection dialInternal(DeferDial deferDial)
            throws CallStateException {
        Log.d(this, "AcrossSubDialHandler dialInternal deferDial = " + deferDial);
        String number = (mConnToDial.getAddress() != null)
                ? mConnToDial.getAddress().getSchemeSpecificPart()
                : "";
        com.android.internal.telephony.Connection originalConnection =
                mPhone.dial(number, new ImsPhone.ImsDialArgs.Builder()
                        .setVideoState(mVideoState)
                        .setIntentExtras(mExtras)
                        .setRttTextStream(mConnToDial.getRttTextStream())
                        .setDeferDial(deferDial)
                        .build());
        if (originalConnection == null) {
            Log.d(this, "AcrossSubDialHandler originalconnection = null. MMI use case");
        }
        return originalConnection;
    }

    private com.android.internal.telephony.Connection startConferenceInternal(DeferDial deferDial)
            throws CallStateException {
        Log.d(this, "AcrossSubDialHandler startConferenceInternal deferDial = " + deferDial);
        com.android.internal.telephony.Connection originalConnection = mPhone.startConference(
                mParticipants, new ImsPhone.ImsDialArgs.Builder()
                        .setVideoState(mVideoState)
                        .setRttTextStream(mConnToDial.getRttTextStream())
                        .setDeferDial(deferDial)
                        .build());
        return originalConnection;
    }

    private void handleDialException(CallStateException e, String msg) {
        Log.e(this, e, msg + " exception: " + e);
        mConnectionService.handleCallStateException(e, mConnToDial, mPhone);
        onCompleted(false);
    }

    private void disconnectAndDial() throws CallStateException {
        // Calls on the non DIALING sub are yet to be disconnected. Defer.ENABLE will
        // result in connection getting created in ImsPhoneCallTracker without placing
        // the call. The call will be placed when DeferDial.DISABLE is sent out
        try {
            mOriginalConnection = (mIsConference ? startConferenceInternal(DeferDial.ENABLE) :
                    dialInternal(DeferDial.ENABLE));

            if (mOriginalConnection == null) {
                // Tracker was able to process MMI code
                Log.d(this, "AcrossSubDialHandler: MMI processed successfully");
                onCompleted(true);
            } else {
                disconnectInternal();
            }
        } catch (CallStateException e) {
            if (e.getError() == CallStateException.ERROR_HOLD_ACTIVE_CALL_ON_OTHER_SUB) {
                // This error means that the tracker can process the MMI code only after the
                // calls on the other sub are disconnected
                Log.d(this, "AcrossSubDialHandler: Wait for calls on other SUB to " +
                            "disconnect before dialing MMI");
                disconnectInternal();
                return;
            }
            onCompleted(false);
            throw e;
        }
    }

    private void disconnectInternal() {
        Collection<Connection> allConnections = mConnectionService.getAllConnections();
        Collection<Conference> allConferences = mConnectionService.getAllConferences();

        for (Connection current : allConnections) {
            // Do not disconnect calls on the SUB where call was dialed
            if (!(current instanceof TelephonyConnection) || Objects.equals(
                        current.getPhoneAccountHandle(), mConnToDial.getPhoneAccountHandle())) {
                continue;
            }
            boolean containsConnection = false;
            containsConnection = mConnectionList.contains(current);
            if (!containsConnection) {
                addConnection(current);
                TelephonyConnection conn = (TelephonyConnection) current;
                conn.addTelephonyConnectionListener(this);
                conn.onDisconnect();
            }
        }
        for (Conference current : allConferences) {
            if (!(current instanceof ImsConference) || Objects.equals(
                        ((ImsConference)current).getConferenceHost().getPhoneAccountHandle(),
                        mConnToDial.getPhoneAccountHandle())) {
                continue;
            }

            boolean containsConference = false;
            containsConference = mConferenceList.contains(current);
            if (!containsConference) {
                addConference(current);
                TelephonyConferenceBase conf = (TelephonyConferenceBase) current;
                conf.addTelephonyConferenceListener(mTelephonyConferenceListener);
                current.onDisconnect();
            }
        }
    }

    private boolean maybeDial() throws CallStateException {
        boolean isConnectionListEmpty = false;
        isConnectionListEmpty = mConnectionList.isEmpty();
        boolean isConferenceListEmpty = false;
        isConferenceListEmpty = mConferenceList.isEmpty();
        if (isConnectionListEmpty && isConferenceListEmpty) {
            Log.d(this, "AcrossSubDialHandler: Ready to dial as all calls on other sub are " +
                        " disconnected");
            mOriginalConnection = (mIsConference ? startConferenceInternal(DeferDial.DISABLE) :
                    dialInternal(DeferDial.DISABLE));
            onCompleted(true);
            return true;
        }
        return false;
    }

    private void addConnection(Connection conn) {
        mConnectionList.add(conn);
    }

    private void removeConnection(Connection conn) {
        mConnectionList.remove(conn);
    }

    private void addConference(Conference conf) {
        mConferenceList.add(conf);
    }

    private void removeConference(Conference conf) {
        mConferenceList.remove(conf);
    }

    @Override
    public void onStateChanged(android.telecom.Connection c, int state) {
        if (mIsDsdsTransition) return;
        Log.d(this, "onStateChanged state = " + state);
        if (c != mConnToHold || !(state == Connection.STATE_HOLDING ||
                state == Connection.STATE_DISCONNECTED)) {
            return;
        }
        if (mOriginalConnection != null &&
                mConnToDial.getState() == Connection.STATE_DISCONNECTED) {
            // Pending MO call was hung up
            onCompleted(false);
            return;
        }
        try {
            com.android.internal.telephony.Connection originalConnection =
                    (mIsConference ? startConferenceInternal(DeferDial.DISABLE) :
                            dialInternal(DeferDial.DISABLE));
            if (mOriginalConnection != originalConnection) {
                // Safe check. This should not happen
                Log.e(this, null,
                        "original connection is different " + mOriginalConnection
                                + " " + originalConnection);
                mConnToDial.setTelephonyConnectionDisconnected(
                        DisconnectCauseUtil.toTelecomDisconnectCause(
                                android.telephony.DisconnectCause.OUTGOING_FAILURE,
                                "original connection is different", mPhone.getPhoneId()));
                // This clears mOriginalConnection as that is the on associated with
                // TelephonyConnection. originalConnection will not be cleared
                mConnToDial.close();
                onCompleted(false);
                return;
            }
            onCompleted(true);
        } catch (CallStateException e) {
            handleDialException(e, "outgoing call failed");
        }
    }

    @Override
    public void onConnectionEvent(Connection c, String event, Bundle extras) {
        if (mIsDsdsTransition || c != mConnToHold) return;
        if (event == android.telecom.Connection.EVENT_CALL_HOLD_FAILED) {
            // Disconnect dialing call
            ImsPhoneConnection conn = (ImsPhoneConnection)mOriginalConnection;
            //set cause similar to same sub hold fail case
            conn.setDisconnectCause(DisconnectCause.ERROR_UNSPECIFIED);
            mConnToDial.onDisconnect();
            onCompleted(false);
        }
    }

    @Override
    public void onDisconnected(android.telecom.Connection c,
                               android.telecom.DisconnectCause disconnectCause) {
        Log.d(this, "AcrossSubDialHandler: onDisconnected connection = " + c);
        if (!(c instanceof TelephonyConnection)) return;
        TelephonyConnection conn = (TelephonyConnection) c;
        conn.removeTelephonyConnectionListener(this);
        removeConnection(c);
        try {
            maybeDial();
        } catch (CallStateException e) {
            handleDialException(e, "outgoing call failed");
        }
    }
}
