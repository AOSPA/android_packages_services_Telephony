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

package com.android.services.telephony;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.telecom.BluetoothCallQualityReport;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.phone.R;

/**
 * class to handle call quality events that are received by telecom and telephony
 */
public class CallQualityManager {
    private static final String TAG = CallQualityManager.class.getCanonicalName();
    private static final String CALL_QUALITY_REPORT_CHANNEL = "call_quality_report_channel";

    /** notification ids */
    public static final int BLUETOOTH_CHOPPY_VOICE_NOTIFICATION_ID = 700;

    public static final String CALL_QUALITY_CHANNEL_ID = "CallQualityNotification";

    private final Context mContext;
    private final NotificationChannel mNotificationChannel;
    private final NotificationManager mNotificationManager;

    public CallQualityManager(Context context) {
        mContext = context;
        mNotificationChannel = new NotificationChannel(CALL_QUALITY_CHANNEL_ID,
                mContext.getString(R.string.call_quality_notification_name),
                NotificationManager.IMPORTANCE_HIGH);
        mNotificationManager = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.createNotificationChannel(mNotificationChannel);
    }

    /**
     * method that is called whenever a
     * {@code BluetoothCallQualityReport.EVENT_SEND_BLUETOOTH_CALL_QUALITY_REPORT} is received
     * @param extras Bundle that includes serialized {@code BluetoothCallQualityReport} parcelable
     */
    @VisibleForTesting
    public void onBluetoothCallQualityReported(Bundle extras) {
        if (extras == null) {
            Log.d(TAG, "onBluetoothCallQualityReported: no extras provided");
        }

        BluetoothCallQualityReport callQualityReport = extras.getParcelable(
                BluetoothCallQualityReport.EXTRA_BLUETOOTH_CALL_QUALITY_REPORT);

        if (callQualityReport.isChoppyVoice()) {
            onChoppyVoice();
        }
        // TODO: once other signals are also sent, we will add more actions here
    }

    /**
     * method to post a notification to user suggesting ways to improve call quality in case of
     * bluetooth choppy voice
     */
    @VisibleForTesting
    public void onChoppyVoice() {
        String title = "Call Quality Improvement";
        //TODO: update call_quality_bluetooth_enhancement_suggestion with below before submitting:
//        "Voice is not being transmitted properly via your bluetooth device."
//                + "To improve, try:\n"
//                + "1. moving your phone closer to your bluetooth device\n"
//                + "2. using a different bluetooth device, or your phone's speaker\n";
        popUpNotification(title,
                mContext.getText(R.string.call_quality_notification_bluetooth_details));
    }

    private void popUpNotification(String title, CharSequence details) {
        int iconId = android.R.drawable.stat_notify_error;

        Notification notification = new Notification.Builder(mContext)
                .setSmallIcon(iconId)
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true)
                .setContentTitle(title)
                .setContentText(details)
                .setStyle(new Notification.BigTextStyle().bigText(details))
                .setAutoCancel(true)
                .setChannelId(CALL_QUALITY_CHANNEL_ID)
                .setOnlyAlertOnce(true)
                .build();

        mNotificationManager.notify(TAG, BLUETOOTH_CHOPPY_VOICE_NOTIFICATION_ID, notification);
    }
}
