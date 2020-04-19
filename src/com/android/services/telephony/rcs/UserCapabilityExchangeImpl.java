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

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.telephony.AccessNetworkConstants;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ProvisioningManager;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.RcsUceAdapter;
import android.telephony.ims.RegistrationManager;
import android.telephony.ims.aidl.IRcsUceControllerCallback;
import android.telephony.ims.aidl.IRcsUcePublishStateCallback;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.RcsCapabilityExchange;
import android.telephony.ims.stub.RcsPresenceExchangeImplBase;
import android.util.Log;

import com.android.ims.RcsFeatureManager;
import com.android.ims.RcsFeatureManager.RcsFeatureCallbacks;
import com.android.ims.ResultCode;
import com.android.internal.annotations.VisibleForTesting;
import com.android.phone.R;
import com.android.service.ims.presence.ContactCapabilityResponse;
import com.android.service.ims.presence.PresenceBase;
import com.android.service.ims.presence.PresencePublication;
import com.android.service.ims.presence.PresencePublisher;
import com.android.service.ims.presence.PresenceSubscriber;
import com.android.service.ims.presence.SubscribePublisher;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Implements User Capability Exchange using Presence.
 */
public class UserCapabilityExchangeImpl implements RcsFeatureController.Feature, SubscribePublisher,
        PresencePublisher {

    private static final String LOG_TAG = "RcsUceImpl";

    private final int mSlotId;
    private volatile int mSubId;
    private volatile boolean mImsContentChangedCallbackRegistered = false;
    // The result of requesting publish
    private volatile int mPublishState = PresenceBase.PUBLISH_STATE_NOT_PUBLISHED;
    // The network type which IMS registers on
    private volatile int mNetworkRegistrationType = AccessNetworkConstants.TRANSPORT_TYPE_INVALID;
    // The MMTel capabilities of this subscription Id
    private MmTelFeature.MmTelCapabilities mMmTelCapabilities;
    private final Object mCapabilitiesLock = new Object();

    private final Context mContext;
    private final UceImplHandler mUceImplHandler;
    private RcsFeatureManager mRcsFeatureManager;

    private final PresencePublication mPresencePublication;
    private final PresenceSubscriber mPresenceSubscriber;

    // The task Ids of updating capabilities
    private final Set<Integer> mRequestingPublishTaskIds = new HashSet<>();

    // The callbacks to notify publish state changed.
    private final RemoteCallbackList<IRcsUcePublishStateCallback> mPublishStateCallbacks;

    private final ConcurrentHashMap<Integer, IRcsUceControllerCallback> mPendingCapabilityRequests =
            new ConcurrentHashMap<>();

    UserCapabilityExchangeImpl(Context context, int slotId, int subId) {
        mSlotId = slotId;
        mSubId = subId;
        logi("created");

        mContext = context;
        mPublishStateCallbacks = new RemoteCallbackList<>();

        HandlerThread handlerThread = new HandlerThread("UceImplHandlerThread");
        handlerThread.start();
        mUceImplHandler = new UceImplHandler(this, handlerThread.getLooper());

        String[] volteError = context.getResources().getStringArray(
                R.array.config_volte_provision_error_on_publish_response);
        String[] rcsError = context.getResources().getStringArray(
                R.array.config_rcs_provision_error_on_publish_response);

        // Initialize PresencePublication
        mPresencePublication = new PresencePublication(null /*PresencePublisher*/, context,
                volteError, rcsError);
        // Initialize PresenceSubscriber
        mPresenceSubscriber = new PresenceSubscriber(null /*SubscribePublisher*/, context,
                volteError, rcsError);

        onAssociatedSubscriptionUpdated(mSubId);
        registerReceivers();
    }

    @VisibleForTesting
    UserCapabilityExchangeImpl(Context context, int slotId, int subId, Looper looper,
            PresencePublication presencePublication, PresenceSubscriber presenceSubscriber,
            RemoteCallbackList<IRcsUcePublishStateCallback> publishStateCallbacks) {
        mSlotId = slotId;
        mSubId = subId;
        mContext = context;
        mPublishStateCallbacks = publishStateCallbacks;
        mUceImplHandler = new UceImplHandler(this, looper);
        mPresencePublication = presencePublication;
        mPresenceSubscriber = presenceSubscriber;
        onAssociatedSubscriptionUpdated(mSubId);
        registerReceivers();
    }

    // Runs on main thread.
    @Override
    public void onRcsConnected(RcsFeatureManager rcsFeatureManager) {
        logi("onRcsConnected");
        mRcsFeatureManager = rcsFeatureManager;
        mRcsFeatureManager.addFeatureListenerCallback(mRcsFeatureCallback);

        mPresencePublication.updatePresencePublisher(this);
        mPresenceSubscriber.updatePresenceSubscriber(this);
    }

    // Runs on main thread.
    @Override
    public void onRcsDisconnected() {
        logi("onRcsDisconnected");
        mPresencePublication.removePresencePublisher();
        mPresenceSubscriber.removePresenceSubscriber();

        if (mRcsFeatureManager != null) {
            mRcsFeatureManager.releaseConnection();
            mRcsFeatureManager = null;
        }
    }

    // Runs on main thread.
    @Override
    public void onAssociatedSubscriptionUpdated(int subId) {
        logi("onAssociatedSubscriptionUpdated: new subId=" + subId);

        // Listen to the IMS content changed with new subId.
        mUceImplHandler.registerImsContentChangedReceiver(subId);

        mSubId = subId;
        mPresencePublication.handleAssociatedSubscriptionChanged(subId);
        mPresenceSubscriber.handleAssociatedSubscriptionChanged(subId);
    }

    /**
     * Should be called before destroying this instance.
     * This instance is not usable after this method is called.
     */
    // Called on main thread.
    public void onDestroy() {
        logi("onDestroy");
        mUceImplHandler.getLooper().quit();
        unregisterReceivers();
        unregisterImsProvisionCallback(mSubId);
        onRcsDisconnected();
    }

    /**
     * @return the UCE Publish state.
     */
    // May happen on a Binder thread, PresencePublication locks to get result.
    public int getUcePublishState() {
        int publishState = mPresencePublication.getPublishState();
        return toUcePublishState(publishState);
    }

    @VisibleForTesting
    public UceImplHandler getHandler() {
        return mUceImplHandler;
    }

    /**
     * Register receiver to receive UCE publish state changed.
     */
    public void registerPublishStateCallback(IRcsUcePublishStateCallback c) {
        synchronized (mPublishStateCallbacks) {
            mPublishStateCallbacks.register(c);
        }
    }

    /**
     * Unregister UCE publish state callback.
     */
    public void unregisterUcePublishStateCallback(IRcsUcePublishStateCallback c) {
        synchronized (mPublishStateCallbacks) {
            mPublishStateCallbacks.unregister(c);
        }
    }

    private void clearPublishStateCallbacks() {
        synchronized (mPublishStateCallbacks) {
            logi("clearPublishStateCallbacks");
            final int lastIndex = mPublishStateCallbacks.getRegisteredCallbackCount() - 1;
            for (int index = lastIndex; index >= 0; index--) {
                IRcsUcePublishStateCallback callback =
                        mPublishStateCallbacks.getRegisteredCallbackItem(index);
                mPublishStateCallbacks.unregister(callback);
            }
        }
    }

    private void notifyPublishStateChanged(@PresenceBase.PresencePublishState int state) {
        int result = toUcePublishState(state);
        mPublishStateCallbacks.broadcast(c -> {
            try {
                c.onPublishStateChanged(result);
            } catch (RemoteException e) {
                logw("notifyPublishStateChanged error: " + e);
            }
        });
    }

    /**
     * Perform a capabilities request and call {@link IRcsUceControllerCallback} with the result.
     */
    // May happen on a Binder thread, PresenceSubscriber locks when requesting Capabilities.
    public void requestCapabilities(List<Uri> contactNumbers, IRcsUceControllerCallback c) {
        List<String> numbers = contactNumbers.stream()
                .map(UserCapabilityExchangeImpl::getNumberFromUri).collect(Collectors.toList());
        int taskId = mPresenceSubscriber.requestCapability(numbers,
                new ContactCapabilityResponse() {
                    @Override
                    public void onSuccess(int reqId) {
                        logi("onSuccess called for reqId:" + reqId);
                    }

                    @Override
                    public void onError(int reqId, int resultCode) {
                        IRcsUceControllerCallback c = mPendingCapabilityRequests.remove(reqId);
                        try {
                            if (c != null) {
                                c.onError(toUceError(resultCode));
                            } else {
                                logw("onError called for unknown reqId:" + reqId);
                            }
                        } catch (RemoteException e) {
                            logi("Calling back to dead service");
                        }
                    }

                    @Override
                    public void onFinish(int reqId) {
                        logi("onFinish called for reqId:" + reqId);
                    }

                    @Override
                    public void onTimeout(int reqId) {
                        IRcsUceControllerCallback c = mPendingCapabilityRequests.remove(reqId);
                        try {
                            if (c != null) {
                                c.onError(RcsUceAdapter.ERROR_REQUEST_TIMEOUT);
                            } else {
                                logw("onTimeout called for unknown reqId:" + reqId);
                            }
                        } catch (RemoteException e) {
                            logi("Calling back to dead service");
                        }
                    }

                    @Override
                    public void onCapabilitiesUpdated(int reqId,
                            List<RcsContactUceCapability> contactCapabilities,
                            boolean updateLastTimestamp) {
                        IRcsUceControllerCallback c = mPendingCapabilityRequests.remove(reqId);
                        try {
                            if (c != null) {
                                c.onCapabilitiesReceived(contactCapabilities);
                            } else {
                                logw("onCapabilitiesUpdated, unknown reqId:" + reqId);
                            }
                        } catch (RemoteException e) {
                            logw("onCapabilitiesUpdated on dead service");
                        }
                    }
                });
        if (taskId < 0) {
            try {
                c.onError(toUceError(taskId));
                return;
            } catch (RemoteException e) {
                logi("Calling back to dead service");
            }
        }
        mPendingCapabilityRequests.put(taskId, c);
    }

    /**
     * The feature callback is to receive the request and update from RcsPresExchangeImplBase
     */
    @VisibleForTesting
    public RcsFeatureCallbacks mRcsFeatureCallback = new RcsFeatureCallbacks() {
        public void onCommandUpdate(int commandCode, int operationToken) {
            logi("onCommandUpdate: code=" + commandCode + ", token=" + operationToken);
            onCommandUpdateForPublishRequest(commandCode, operationToken);
        }

        /** See {@link RcsPresenceExchangeImplBase#onNetworkResponse(int, String, int)} */
        public void onNetworkResponse(int responseCode, String reason, int operationToken) {
            logi("onNetworkResponse: code=" + responseCode + ", reason=" + reason
                    + ", operationToken=" + operationToken);
            onNetworkResponseForPublishRequest(responseCode, reason, operationToken);
        }

        /** See {@link RcsPresenceExchangeImplBase#onCapabilityRequestResponse(List, int)} */
        public void onCapabilityRequestResponsePresence(List<RcsContactUceCapability> infos,
                int operationToken) {

        }

        /** See {@link RcsPresenceExchangeImplBase#onNotifyUpdateCapabilites(int)} */
        public void onNotifyUpdateCapabilities(int publishTriggerType) {
            logi("onNotifyUpdateCapabilities: type=" + publishTriggerType);
            mUceImplHandler.notifyUpdateCapabilities(publishTriggerType);
        }

        /** See {@link RcsPresenceExchangeImplBase#onUnpublish()} */
        public void onUnpublish() {
            logi("onUnpublish");
            mUceImplHandler.unpublish();
        }
    };

    private static class UceImplHandler extends Handler {
        private static final int EVENT_REGISTER_IMS_CHANGED_RECEIVER = 1;
        private static final int EVENT_NOTIFY_UPDATE_CAPABILITIES = 2;
        private static final int EVENT_UNPUBLISH = 3;

        private static final int REGISTER_IMS_CHANGED_DELAY = 10000;  //10 seconds

        private final WeakReference<UserCapabilityExchangeImpl> mUceImplRef;

        UceImplHandler(UserCapabilityExchangeImpl uceImpl, Looper looper) {
            super(looper);
            mUceImplRef = new WeakReference(uceImpl);
        }

        @Override
        public void handleMessage(Message msg) {
            UserCapabilityExchangeImpl uceImpl = mUceImplRef.get();
            if (uceImpl == null) {
                return;
            }
            switch (msg.what) {
                case EVENT_REGISTER_IMS_CHANGED_RECEIVER:
                    int subId = msg.arg1;
                    uceImpl.registerImsContentChangedReceiverInternal(subId);
                    break;
                case EVENT_NOTIFY_UPDATE_CAPABILITIES:
                    int publishTriggerType = msg.arg1;
                    uceImpl.onNotifyUpdateCapabilities(publishTriggerType);
                    break;
                case EVENT_UNPUBLISH:
                    uceImpl.updatePublisherState(PresenceBase.PUBLISH_STATE_NOT_PUBLISHED);
                    break;
                default:
                    Log.w(LOG_TAG, "handleMessage: error=" + msg.what);
                    break;
            }
        }

        private void retryRegisteringImsContentChangedReceiver(int subId) {
            sendRegisteringImsContentChangedMessage(subId, REGISTER_IMS_CHANGED_DELAY);
        }

        private void registerImsContentChangedReceiver(int subId) {
            sendRegisteringImsContentChangedMessage(subId, 0);
        }

        private void sendRegisteringImsContentChangedMessage(int subId, int delay) {
            if (subId <= SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return;
            }
            removeRegisteringImsContentChangedReceiver();
            Message message = obtainMessage(EVENT_REGISTER_IMS_CHANGED_RECEIVER);
            message.arg1 = subId;
            sendMessageDelayed(message, delay);
        }

        private void removeRegisteringImsContentChangedReceiver() {
            removeMessages(EVENT_REGISTER_IMS_CHANGED_RECEIVER);
        }

        private void notifyUpdateCapabilities(int publishTriggerType) {
            Message message = obtainMessage(EVENT_NOTIFY_UPDATE_CAPABILITIES);
            message.arg1 = publishTriggerType;
            sendMessage(message);
        }

        private void unpublish() {
            sendEmptyMessage(EVENT_UNPUBLISH);
        }
    }

    private void onNotifyUpdateCapabilities(int publishTriggerType) {
        mPresencePublication.onStackPublishRequested(publishTriggerType);
    }

    @Override
    public @PresenceBase.PresencePublishState int getPublisherState() {
        return mPublishState;
    }

    @Override
    public int requestPublication(RcsContactUceCapability capabilities, String contactUri,
            int taskId) {
        if (mRcsFeatureManager == null) {
            logw("requestPublication error: RcsFeatureManager is null.");
            return ResultCode.ERROR_SERVICE_NOT_AVAILABLE;
        }

        logi("requestPublication: taskId=" + taskId);
        addPublishRequestTaskId(taskId);

        try {
            mRcsFeatureManager.requestPublication(capabilities, taskId);
        } catch (Exception ex) {
            logw("requestPublication error: " + ex.getMessage());
            removePublishRequestTaskId(taskId);
            return ResultCode.PUBLISH_GENERIC_FAILURE;
        }
        return ResultCode.SUCCESS;
    }

    /*
     * Handle the callback method RcsFeatureCallbacks#onCommandUpdate(int, int)
     */
    private void onCommandUpdateForPublishRequest(int commandCode, int operationToken) {
        if (!isPublishRequestExisted(operationToken)) {
            return;
        }
        int resultCode = ResultCode.SUCCESS;
        if (commandCode != RcsCapabilityExchange.COMMAND_CODE_SUCCESS) {
            logw("Command is failed: taskId=" + operationToken + ", code=" + commandCode);
            removePublishRequestTaskId(operationToken);
            resultCode = ResultCode.PUBLISH_GENERIC_FAILURE;
        }
        mPresencePublication.onCommandStatusUpdated(operationToken, operationToken, resultCode);
    }

    /*
     * Handle the callback method RcsFeatureCallbacks#onNetworkResponse(int, String, int)
     */
    private void onNetworkResponseForPublishRequest(int responseCode, String reason,
            int operationToken) {
        if (!isPublishRequestExisted(operationToken)) {
            return;
        }
        removePublishRequestTaskId(operationToken);
        mPresencePublication.onSipResponse(operationToken, responseCode, reason);
    }

    @Override
    public int requestCapability(String[] formatedContacts, int taskId) {
        return 0;
    }

    @Override
    public int requestAvailability(String formattedContact, int taskId) {
        return 0;
    }

    @Override
    public int getStackStatusForCapabilityRequest() {
        return 0;
    }

    @Override
    public void updatePublisherState(@PresenceBase.PresencePublishState int publishState) {
        logi("updatePublisherState: from " + mPublishState + " to " + publishState);
        mPublishState = publishState;
        notifyPublishStateChanged(publishState);
    }

    private void addPublishRequestTaskId(int taskId) {
        synchronized (mRequestingPublishTaskIds) {
            mRequestingPublishTaskIds.add(taskId);
        }
    }

    private void removePublishRequestTaskId(int taskId) {
        synchronized (mRequestingPublishTaskIds) {
            mRequestingPublishTaskIds.remove(taskId);
        }
    }

    private boolean isPublishRequestExisted(Integer taskId) {
        synchronized (mRequestingPublishTaskIds) {
            return mRequestingPublishTaskIds.contains(taskId);
        }
    }

    private static String getNumberFromUri(Uri uri) {
        String number = uri.getSchemeSpecificPart();
        String[] numberParts = number.split("[@;:]");

        if (numberParts.length == 0) {
            return null;
        }
        return numberParts[0];
    }

    private static int toUcePublishState(int publishState) {
        switch (publishState) {
            case PresenceBase.PUBLISH_STATE_200_OK:
                return RcsUceAdapter.PUBLISH_STATE_OK;
            case PresenceBase.PUBLISH_STATE_NOT_PUBLISHED:
                return RcsUceAdapter.PUBLISH_STATE_NOT_PUBLISHED;
            case PresenceBase.PUBLISH_STATE_VOLTE_PROVISION_ERROR:
                return RcsUceAdapter.PUBLISH_STATE_VOLTE_PROVISION_ERROR;
            case PresenceBase.PUBLISH_STATE_RCS_PROVISION_ERROR:
                return RcsUceAdapter.PUBLISH_STATE_RCS_PROVISION_ERROR;
            case PresenceBase.PUBLISH_STATE_REQUEST_TIMEOUT:
                return RcsUceAdapter.PUBLISH_STATE_REQUEST_TIMEOUT;
            case PresenceBase.PUBLISH_STATE_OTHER_ERROR:
                return RcsUceAdapter.PUBLISH_STATE_OTHER_ERROR;
            default:
                return RcsUceAdapter.PUBLISH_STATE_OTHER_ERROR;
        }
    }

    private static int toUceError(int resultCode) {
        switch (resultCode) {
            case ResultCode.SUBSCRIBE_NOT_REGISTERED:
                return RcsUceAdapter.ERROR_NOT_REGISTERED;
            case ResultCode.SUBSCRIBE_REQUEST_TIMEOUT:
                return RcsUceAdapter.ERROR_REQUEST_TIMEOUT;
            case ResultCode.SUBSCRIBE_FORBIDDEN:
                return RcsUceAdapter.ERROR_FORBIDDEN;
            case ResultCode.SUBSCRIBE_NOT_FOUND:
                return RcsUceAdapter.ERROR_NOT_FOUND;
            case ResultCode.SUBSCRIBE_TOO_LARGE:
                return RcsUceAdapter.ERROR_REQUEST_TOO_LARGE;
            case ResultCode.SUBSCRIBE_INSUFFICIENT_MEMORY:
                return RcsUceAdapter.ERROR_INSUFFICIENT_MEMORY;
            case ResultCode.SUBSCRIBE_LOST_NETWORK:
                return RcsUceAdapter.ERROR_LOST_NETWORK;
            case ResultCode.SUBSCRIBE_ALREADY_IN_QUEUE:
                return RcsUceAdapter.ERROR_ALREADY_IN_QUEUE;
            default:
                return RcsUceAdapter.ERROR_GENERIC_FAILURE;
        }
    }

    /*
     * Register receivers for updating capabilities
     */
    private void registerReceivers() {
        IntentFilter filter = new IntentFilter(TelecomManager.ACTION_TTY_PREFERRED_MODE_CHANGED);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        mContext.registerReceiver(mReceiver, filter);

        ContentResolver resolver = mContext.getContentResolver();
        if (resolver != null) {
            // Register mobile data content changed.
            resolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.MOBILE_DATA), false,
                    mMobileDataObserver);

            // Register SIM info content changed.
            resolver.registerContentObserver(Telephony.SimInfo.CONTENT_URI, false,
                    mSimInfoContentObserver);
        }
    }

    private void unregisterReceivers() {
        mContext.unregisterReceiver(mReceiver);
        ContentResolver resolver = mContext.getContentResolver();
        if (resolver != null) {
            resolver.unregisterContentObserver(mMobileDataObserver);
            resolver.unregisterContentObserver(mSimInfoContentObserver);
        }
    }

    /**
     * Register IMS and provision content changed.
     *
     * Call the UceImplHandler#registerImsContentChangedReceiver instead of
     * calling this method directly.
     */
    private void registerImsContentChangedReceiverInternal(int subId) {
        mUceImplHandler.removeRegisteringImsContentChangedReceiver();
        try {
            final int originalSubId = mSubId;
            if ((originalSubId == subId) && (mImsContentChangedCallbackRegistered)) {
                logi("registerImsContentChangedReceiverInternal: already registered. skip");
                return;
            }
            // Unregister original IMS and Provision callback
            unregisterImsProvisionCallback(originalSubId);
            // Register new IMS and Provision callback
            registerImsProvisionCallback(subId);
        } catch (ImsException e) {
            logw("registerImsContentChangedReceiverInternal error: " + e);
            mUceImplHandler.retryRegisteringImsContentChangedReceiver(subId);
        }
    }

    private void unregisterImsProvisionCallback(int subId) {
        if (subId <= SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return;
        }
        // Unregister IMS callback
        ImsMmTelManager imsMmtelManager = getImsMmTelManager(subId);
        if (imsMmtelManager != null) {
            try {
                imsMmtelManager.unregisterImsRegistrationCallback(mImsRegistrationCallback);
                imsMmtelManager.unregisterMmTelCapabilityCallback(mCapabilityCallback);
            } catch (RuntimeException e) {
                logw("unregister IMS callback error: " + e.getMessage());
            }
        }

        // Unregister provision changed callback
        ProvisioningManager provisioningManager =
                ProvisioningManager.createForSubscriptionId(subId);
        try {
            provisioningManager.unregisterProvisioningChangedCallback(mProvisioningChangedCallback);
        } catch (RuntimeException e) {
            logw("unregister provisioning callback error: " + e.getMessage());
        }

        // Remove all publish state callbacks
        clearPublishStateCallbacks();

        mImsContentChangedCallbackRegistered = false;
    }

    private void registerImsProvisionCallback(int subId) throws ImsException {
        if (subId <= SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return;
        }
        // Register IMS callback
        ImsMmTelManager imsMmtelManager = getImsMmTelManager(subId);
        if (imsMmtelManager != null) {
            imsMmtelManager.registerImsRegistrationCallback(mContext.getMainExecutor(),
                    mImsRegistrationCallback);
            imsMmtelManager.registerMmTelCapabilityCallback(mContext.getMainExecutor(),
                    mCapabilityCallback);
        }
        // Register provision changed callback
        ProvisioningManager provisioningManager =
                ProvisioningManager.createForSubscriptionId(subId);
        provisioningManager.registerProvisioningChangedCallback(mContext.getMainExecutor(),
                mProvisioningChangedCallback);

        mImsContentChangedCallbackRegistered = true;
        logi("registerImsProvisionCallback");
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            switch (intent.getAction()) {
                case TelecomManager.ACTION_TTY_PREFERRED_MODE_CHANGED:
                    int preferredMode = intent.getIntExtra(
                            TelecomManager.EXTRA_TTY_PREFERRED_MODE, TelecomManager.TTY_MODE_OFF);
                    logi("TTY preferred mode changed: " + preferredMode);
                    mPresencePublication.onTtyPreferredModeChanged(preferredMode);
                    break;

                case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                    boolean airplaneMode = intent.getBooleanExtra("state", false);
                    logi("Airplane mode changed: " + airplaneMode);
                    mPresencePublication.onAirplaneModeChanged(airplaneMode);
                    break;
            }
        }
    };

    private ContentObserver mMobileDataObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            boolean isEnabled = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.MOBILE_DATA, 1) == 1;
            logi("Mobile data changed: enabled=" + isEnabled);
            mPresencePublication.onMobileDataChanged(isEnabled);
        }
    };

    private ContentObserver mSimInfoContentObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            if (mSubId <= SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return;
            }

            ImsMmTelManager ims = getImsMmTelManager(mSubId);
            if (ims == null) return;

            try {
                boolean isEnabled = ims.isVtSettingEnabled();
                logi("SimInfo changed: VT setting=" + isEnabled);
                mPresencePublication.onVtEnabled(isEnabled);
            } catch (RuntimeException e) {
                logw("SimInfo changed error: " + e);
            }
        }
    };

    private RegistrationManager.RegistrationCallback mImsRegistrationCallback =
            new RegistrationManager.RegistrationCallback() {
        @Override
        public void onRegistered(int imsTransportType) {
            logi("onRegistered: type=" + imsTransportType);
            mNetworkRegistrationType = imsTransportType;
            mPresencePublication.onImsConnected();

            // Also trigger PresencePublication#onFeatureCapabilityChanged method
            MmTelFeature.MmTelCapabilities capabilities = null;
            synchronized (mCapabilitiesLock) {
                capabilities = mMmTelCapabilities;
            }
            mPresencePublication.onFeatureCapabilityChanged(mNetworkRegistrationType, capabilities);
        }

        @Override
        public void onUnregistered(ImsReasonInfo info) {
            logi("onUnregistered");
            mNetworkRegistrationType = AccessNetworkConstants.TRANSPORT_TYPE_INVALID;

            // Also trigger PresencePublication#onFeatureCapabilityChanged method
            MmTelFeature.MmTelCapabilities capabilities = null;
            synchronized (mCapabilitiesLock) {
                capabilities = mMmTelCapabilities;
            }
            mPresencePublication.onFeatureCapabilityChanged(mNetworkRegistrationType, capabilities);
            mPresencePublication.onImsDisconnected();
        }
    };

    private ImsMmTelManager.CapabilityCallback mCapabilityCallback =
            new ImsMmTelManager.CapabilityCallback() {
        @Override
        public void onCapabilitiesStatusChanged(MmTelFeature.MmTelCapabilities capabilities) {
            synchronized (mCapabilitiesLock) {
                mMmTelCapabilities = capabilities;
            }
            mPresencePublication.onFeatureCapabilityChanged(mNetworkRegistrationType, capabilities);
        }
    };

    private ProvisioningManager.Callback mProvisioningChangedCallback =
            new ProvisioningManager.Callback() {
        @Override
        public void onProvisioningIntChanged(int item, int value) {
            logi("onProvisioningIntChanged: item=" + item);
            switch (item) {
                case ProvisioningManager.KEY_EAB_PROVISIONING_STATUS:
                case ProvisioningManager.KEY_VOLTE_PROVISIONING_STATUS:
                case ProvisioningManager.KEY_VT_PROVISIONING_STATUS:
                    mPresencePublication.handleProvisioningChanged();
                    break;
                default:
                    break;
            }
        }
    };

    private ImsMmTelManager getImsMmTelManager(int subId) {
        try {
            ImsManager imsManager = (ImsManager) mContext.getSystemService(
                    Context.TELEPHONY_IMS_SERVICE);
            return (imsManager == null) ? null : imsManager.getImsMmTelManager(subId);
        } catch (IllegalArgumentException e) {
            logw("getImsMmTelManager error: " + e.getMessage());
            return null;
        }
    }

    private void logi(String log) {
        Log.i(LOG_TAG, getLogPrefix().append(log).toString());
    }

    private void logw(String log) {
        Log.w(LOG_TAG, getLogPrefix().append(log).toString());
    }

    private StringBuilder getLogPrefix() {
        StringBuilder builder = new StringBuilder("[");
        builder.append(mSlotId);
        builder.append("->");
        builder.append(mSubId);
        builder.append("] ");
        return builder;
    }
}
