/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.os.Bundle;
import android.os.PersistableBundle;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.telephony.CarrierConfigManager;
import android.view.MenuItem;

import com.android.internal.telephony.PhoneConstants;

public class CdmaCallOptions extends TimeConsumingPreferenceActivity {
    private static final String LOG_TAG = "CdmaCallOptions";
    private final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    private static final String BUTTON_VP_KEY = "button_voice_privacy_key";
    private static final String CALL_FORWARDING_KEY = "call_forwarding_key";
    private static final String CALL_WAITING_KEY = "call_waiting_key";

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.cdma_call_privacy);

        SubscriptionInfoHelper subInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        subInfoHelper.setActionBarTitle(
                getActionBar(), getResources(), R.string.labelCdmaMore_with_label);

        CdmaVoicePrivacySwitchPreference buttonVoicePrivacy =
                (CdmaVoicePrivacySwitchPreference) findPreference(BUTTON_VP_KEY);
        buttonVoicePrivacy.setPhone(subInfoHelper.getPhone());
        PersistableBundle carrierConfig;
        if (subInfoHelper.hasSubId()) {
            carrierConfig = PhoneGlobals.getInstance().getCarrierConfigForSubId(
                    subInfoHelper.getSubId());
        } else {
            carrierConfig = PhoneGlobals.getInstance().getCarrierConfig();
        }
        if (subInfoHelper.getPhone().getPhoneType() != PhoneConstants.PHONE_TYPE_CDMA
                || carrierConfig.getBoolean(CarrierConfigManager.KEY_VOICE_PRIVACY_DISABLE_UI_BOOL)) {
            buttonVoicePrivacy.setEnabled(false);
        }

        Preference callForwardingPref = getPreferenceScreen().findPreference(CALL_FORWARDING_KEY);
        if (carrierConfig != null && carrierConfig.getBoolean(
                CarrierConfigManager.KEY_CALL_FORWARDING_VISIBILITY_BOOL)) {
            callForwardingPref.setIntent(
                    subInfoHelper.getIntent(CdmaCallForwardOptions.class));
        } else {
            getPreferenceScreen().removePreference(callForwardingPref);
        }

        CdmaCallWaitingPreference callWaitingPref = (CdmaCallWaitingPreference)getPreferenceScreen()
                                                     .findPreference(CALL_WAITING_KEY);
        if (carrierConfig != null && carrierConfig.getBoolean(
                CarrierConfigManager.KEY_ADDITIONAL_SETTINGS_CALL_WAITING_VISIBILITY_BOOL)) {
            callWaitingPref.init(this, subInfoHelper.getPhone());
        } else {
            getPreferenceScreen().removePreference(callWaitingPref);
        }
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
