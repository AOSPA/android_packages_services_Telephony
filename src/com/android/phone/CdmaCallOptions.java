/*
 * Copyright (C) 2009,2015 The Android Open Source Project
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

package com.android.phone;

import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.view.MenuItem;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.SubscriptionController;

public class CdmaCallOptions extends PreferenceActivity {
    private static final String LOG_TAG = "CdmaCallOptions";
    private final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    public static final int CALL_WAITING = 7;
    private static final String BUTTON_VP_KEY = "button_voice_privacy_key";

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.cdma_call_privacy);

        SubscriptionInfoHelper subInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        subInfoHelper.setActionBarTitle(
                getActionBar(), getResources(), R.string.labelCdmaMore_with_label);

        PersistableBundle carrierConfig;
        if (subInfoHelper.hasSubId()) {
            carrierConfig = PhoneGlobals.getInstance().getCarrierConfigForSubId(
                    subInfoHelper.getSubId());
        } else {
            carrierConfig = PhoneGlobals.getInstance().getCarrierConfig();
        }

        Phone phone = subInfoHelper.getPhone();
        Log.d(LOG_TAG, "sub id = " + subInfoHelper.getSubId() + " phone id = " +
                phone.getPhoneId());

        PreferenceScreen prefScreen = getPreferenceScreen();
        if (phone.getPhoneType() != PhoneConstants.PHONE_TYPE_CDMA
                || carrierConfig.getBoolean(CarrierConfigManager.KEY_VOICE_PRIVACY_DISABLE_UI_BOOL)) {
            CdmaVoicePrivacyCheckBoxPreference prefPri = (CdmaVoicePrivacyCheckBoxPreference)
                    prefScreen.findPreference("button_voice_privacy_key");
            if (prefPri != null) {
                prefPri.setEnabled(false);
            }
        }

        if (phone.getPhoneType() != PhoneConstants.PHONE_TYPE_CDMA
                || !carrierConfig.getBoolean(CarrierConfigManager.KEY_CDMA_CW_CF_ENABLED_BOOL)) {
            Log.d(LOG_TAG, "Disabled CW CF");
            PreferenceScreen prefCW = (PreferenceScreen)
                    prefScreen.findPreference("button_cw_key");
            if (prefCW != null) {
                prefCW.setEnabled(false);
            }
            PreferenceScreen prefCF = (PreferenceScreen)
                    prefScreen.findPreference("button_cf_expand_key");
            if (prefCF != null) {
                prefCF.setEnabled(false);
            }
        } else {
            Log.d(LOG_TAG, "Enabled CW CF");
            PreferenceScreen prefCF = (PreferenceScreen)
                    prefScreen.findPreference("button_cf_expand_key");
            if (prefCF != null) {
                prefCF.getIntent().putExtra(PhoneConstants.SUBSCRIPTION_KEY, phone.getSubId());
            }
            initCallWaitingPref(this, phone.getPhoneId());
        }
    }

    public static void initCallWaitingPref(PreferenceActivity activity, int phoneId) {
        PreferenceScreen prefCWAct = (PreferenceScreen)
                activity.findPreference("button_cw_act_key");
        PreferenceScreen prefCWDeact = (PreferenceScreen)
                activity.findPreference("button_cw_deact_key");

        CdmaCallOptionsSetting callOptionSettings = new CdmaCallOptionsSetting(activity,
                CALL_WAITING, SubscriptionController.getInstance().getSubIdUsingPhoneId(phoneId));

        PhoneAccountHandle accountHandle = PhoneGlobals.getPhoneAccountHandle(activity, phoneId);
        prefCWAct.getIntent()
                .putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
                .setData(Uri.fromParts("tel", callOptionSettings.getActivateNumber(), null));
        prefCWAct.setSummary(callOptionSettings.getActivateNumber());

        prefCWDeact.getIntent()
                .putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
                .setData(Uri.fromParts("tel", callOptionSettings.getDeactivateNumber(), null));
        prefCWDeact.setSummary(callOptionSettings.getDeactivateNumber());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference.getKey().equals(BUTTON_VP_KEY)) {
            return true;
        }
        return false;
    }

}
