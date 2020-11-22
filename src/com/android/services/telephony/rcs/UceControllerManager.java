/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.services.telephony.rcs;

import android.content.Context;
import android.net.Uri;
import android.telephony.ims.ImsException;
import android.telephony.ims.RcsUceAdapter;
import android.telephony.ims.RcsUceAdapter.PublishState;
import android.telephony.ims.aidl.IRcsUceControllerCallback;
import android.telephony.ims.aidl.IRcsUcePublishStateCallback;
import android.util.Log;

import com.android.ims.RcsFeatureManager;
import com.android.ims.rcs.uce.UceController;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Responsible for managing the creation and destruction of UceController. It also received the
 * requests from {@link com.android.phone.ImsRcsController} and pass these requests to
 * {@link UceController}
 */
public class UceControllerManager implements RcsFeatureController.Feature {

    private static final String LOG_TAG = "UceControllerManager";

    private final int mSlotId;
    private final Context mContext;
    private final ExecutorService mExecutorService;

    private volatile UceController mUceController;

    public UceControllerManager(Context context, int slotId, int subId) {
        Log.d(LOG_TAG, "create: slotId=" + slotId + ", subId=" + subId);

        mSlotId = slotId;
        mContext = context;
        mExecutorService = Executors.newSingleThreadExecutor();
        mUceController = new UceController(mContext, subId);
    }

    /**
     * Constructor to inject dependencies for testing.
     */
    @VisibleForTesting
    public UceControllerManager(Context context, int slotId, int subId, ExecutorService executor) {
        mSlotId = slotId;
        mContext = context;
        mExecutorService = executor;
        mUceController = new UceController(mContext, subId);
    }

    @Override
    public void onRcsConnected(RcsFeatureManager manager) {
        mExecutorService.submit(() -> mUceController.onRcsConnected(manager));
    }

    @Override
    public void onRcsDisconnected() {
        mExecutorService.submit(() -> mUceController.onRcsDisconnected());
    }

    @Override
    public void onDestroy() {
        Log.d(LOG_TAG, "onDestroy");
        mExecutorService.submit(() -> mUceController.onDestroy());
        // When the shutdown is called, it will refuse any new tasks and let existing tasks finish.
        mExecutorService.shutdown();
    }

    /**
     * This method will be called when either the subscription ID associated with the slot has
     * changed or the carrier configuration associated with the same subId has changed.
     */
    @Override
    public void onAssociatedSubscriptionUpdated(int subId) {
        mExecutorService.submit(() -> {
            Log.i(LOG_TAG, "onAssociatedSubscriptionUpdated: slotId=" + mSlotId
                    + ", subId=" + subId);

            // Destroy existing UceController and create a new one.
            mUceController.onDestroy();
            mUceController = new UceController(mContext, subId);
        });
    }

    @VisibleForTesting
    public void setUceController(UceController uceController) {
        mUceController = uceController;
    }

    /**
     * Request the capabilities for contacts.
     *
     * @param contactNumbers A list of numbers that the capabilities are being requested for.
     * @param c A callback for when the request for capabilities completes.
     * @throws ImsException if the ImsService connected to this controller is currently down.
     */
    public void requestCapabilities(List<Uri> contactNumbers, IRcsUceControllerCallback c)
            throws ImsException {
        Future future = mExecutorService.submit(() -> {
            checkUceControllerState();
            mUceController.requestCapabilities(contactNumbers, c);
            return true;
        });

        try {
            future.get();
        } catch (ExecutionException | InterruptedException e) {
            Log.w(LOG_TAG, "requestCapabilities: " + e);
            Throwable cause = e.getCause();
            if (cause instanceof ImsException) {
                throw (ImsException) cause;
            }
        }
    }

    /**
     * Request the capabilities for the given contact.
     * @param contactNumber The contact of the capabilities are being requested for.
     * @param c A callback for when the request for capabilities completes.
     * @throws ImsException if the ImsService connected to this controller is currently down.
     */
    public void requestNetworkAvailability(Uri contactNumber, IRcsUceControllerCallback c)
            throws ImsException {
        Future future = mExecutorService.submit(() -> {
            checkUceControllerState();
            mUceController.requestAvailability(contactNumber, c);
            return true;
        });

        try {
            future.get();
        } catch (ExecutionException | InterruptedException e) {
            Log.w(LOG_TAG, "requestNetworkAvailability exception: " + e);
            Throwable cause = e.getCause();
            if (cause instanceof ImsException) {
                throw (ImsException) cause;
            }
        }
    }

    /**
     * Get the UCE publish state.
     *
     * @throws ImsException if the ImsService connected to this controller is currently down.
     */
    public @PublishState int getUcePublishState() throws ImsException {
        Future future = mExecutorService.submit(() -> {
            checkUceControllerState();
            return mUceController.getUcePublishState();
        });

        try {
            return (Integer) future.get();
        } catch (ExecutionException | InterruptedException e) {
            Log.w(LOG_TAG, "requestNetworkAvailability exception: " + e);
            Throwable cause = e.getCause();
            if (cause instanceof ImsException) {
                throw (ImsException) cause;
            }
            return RcsUceAdapter.PUBLISH_STATE_OTHER_ERROR;
        }
    }

    /**
     * Register the Publish state changed callback.
     *
     * @throws ImsException if the ImsService connected to this controller is currently down.
     */
    public void registerPublishStateCallback(IRcsUcePublishStateCallback c) throws ImsException {
        Future future = mExecutorService.submit(() -> {
            checkUceControllerState();
            mUceController.registerPublishStateCallback(c);
            return true;
        });

        try {
            future.get();
        } catch (ExecutionException | InterruptedException e) {
            Log.w(LOG_TAG, "registerPublishStateCallback exception: " + e);
            Throwable cause = e.getCause();
            if (cause instanceof ImsException) {
                throw (ImsException) cause;
            }
        }
    }

    /**
     * Unregister the existing publish state changed callback.
     */
    public void unregisterPublishStateCallback(IRcsUcePublishStateCallback c) {
        Future future = mExecutorService.submit(() -> {
            if (checkUceControllerState()) {
                mUceController.unregisterPublishStateCallback(c);
            }
            return true;
        });

        try {
            future.get();
        } catch (ExecutionException | InterruptedException e) {
            Log.w(LOG_TAG, "unregisterPublishStateCallback exception: " + e);
        }
    }

    private boolean checkUceControllerState() throws ImsException {
        if (mUceController == null || mUceController.isUnavailable()) {
            throw new ImsException("UCE controller is unavailable",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
        return true;
    }


    @Override
    public void dump(PrintWriter printWriter) {
        IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, "  ");
        pw.println("UceControllerManager" + "[" + mSlotId + "]:");
        pw.increaseIndent();
        pw.println("UceController available = " + mUceController != null);
        //TODO: Add dump for UceController
        pw.decreaseIndent();
    }
}
