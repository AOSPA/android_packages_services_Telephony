package com.android.services.telephony;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;
import static junit.framework.TestCase.assertFalse;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Bundle;
import android.os.PersistableBundle;
import android.telecom.Connection;
import android.telephony.CarrierConfigManager;
import android.telephony.DisconnectCause;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.d2d.DtmfTransport;
import com.android.internal.telephony.d2d.RtpTransport;
import com.android.internal.telephony.imsphone.ImsPhoneConnection;
import com.android.phone.PhoneGlobals;
import com.android.phone.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class TelephonyConnectionTest {
    @Mock
    private ImsPhoneConnection mImsPhoneConnection;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mImsPhoneConnection.getState()).thenReturn(Call.State.ACTIVE);
        when(mImsPhoneConnection.getPhoneType()).thenReturn(PhoneConstants.PHONE_TYPE_IMS);
    }

    /**
     * Ensures an Ims connection uses the D2D communicator when it is enabled.
     */
    @Test
    public void testSetupCommunicator() {
        TestTelephonyConnection c = new TestTelephonyConnection();
        c.setIsImsConnection(true);
        // Enable D2D comms.
        when(c.mMockResources.getBoolean(eq(
                R.bool.config_use_device_to_device_communication))).thenReturn(true);
        c.getCarrierConfigBundle().putBoolean(
                CarrierConfigManager.KEY_SUPPORTS_DEVICE_TO_DEVICE_COMMUNICATION_USING_DTMF_BOOL,
                true);

        c.setOriginalConnection(mImsPhoneConnection);
        assertNotNull(c.getCommunicator());
    }

    /**
     * Ensures an Ims connection does not use the D2D communicator when it is disabled.
     */
    @Test
    public void testDoNotSetupCommunicatorWhenDisabled() {
        TestTelephonyConnection c = new TestTelephonyConnection();
        c.setIsImsConnection(true);
        // Disable D2D comms.
        when(c.mMockResources.getBoolean(eq(
                R.bool.config_use_device_to_device_communication))).thenReturn(false);

        c.setOriginalConnection(mImsPhoneConnection);
        assertNull(c.getCommunicator());
    }

    /**
     * Ensures an Ims connection does not use the D2D communicator for a non-IMS call.
     */
    @Test
    public void testDoNotSetupCommunicatorForNonIms() {
        TestTelephonyConnection c = new TestTelephonyConnection();
        c.setIsImsConnection(false);
        // Disable D2D comms.
        when(c.mMockResources.getBoolean(eq(
                R.bool.config_use_device_to_device_communication))).thenReturn(true);

        c.setOriginalConnection(mImsPhoneConnection);
        assertNull(c.getCommunicator());
    }

    /**
     * Ensures an Ims connection does not use the D2D communicator when it is disabled.
     */
    @Test
    public void testDoNotSetupCommunicatorNoTransports() {
        TestTelephonyConnection c = new TestTelephonyConnection();
        c.setIsImsConnection(true);
        // Enable D2D comms.
        when(c.mMockResources.getBoolean(eq(
                R.bool.config_use_device_to_device_communication))).thenReturn(true);
        // But carrier disables transports.  Womp.
        c.getCarrierConfigBundle().putBoolean(
                CarrierConfigManager.KEY_SUPPORTS_DEVICE_TO_DEVICE_COMMUNICATION_USING_DTMF_BOOL,
                false);
        c.getCarrierConfigBundle().putBoolean(
                CarrierConfigManager.KEY_SUPPORTS_DEVICE_TO_DEVICE_COMMUNICATION_USING_RTP_BOOL,
                false);
        c.setOriginalConnection(mImsPhoneConnection);
        assertNull(c.getCommunicator());
    }

    @Test
    public void testSetupRtpOnly() {
        TestTelephonyConnection c = new TestTelephonyConnection();
        c.setIsImsConnection(true);
        // Enable D2D comms.
        when(c.mMockResources.getBoolean(eq(
                R.bool.config_use_device_to_device_communication))).thenReturn(true);
        // But carrier disables transports.  Womp.
        c.getCarrierConfigBundle().putBoolean(
                CarrierConfigManager.KEY_SUPPORTS_DEVICE_TO_DEVICE_COMMUNICATION_USING_DTMF_BOOL,
                false);
        c.getCarrierConfigBundle().putBoolean(
                CarrierConfigManager.KEY_SUPPORTS_DEVICE_TO_DEVICE_COMMUNICATION_USING_RTP_BOOL,
                true);
        c.setOriginalConnection(mImsPhoneConnection);
        assertNotNull(c.getCommunicator());
        assertEquals(1, c.getCommunicator().getTransportProtocols().size());
        assertTrue(c.getCommunicator().getTransportProtocols()
                .stream().anyMatch(p -> p instanceof RtpTransport));
    }

    @Test
    public void testHangupAfterRedial() throws Exception {
        TestTelephonyConnection c = new TestTelephonyConnection();
        c.hangup(DisconnectCause.LOCAL);
        verify(c.mMockRadioConnection).hangup();

        // hangup failed because redial was in progress... The new original connection has been sent
        // to the TelephonyConnection
        com.android.internal.telephony.Connection newMockRadioConnection =
                mock(com.android.internal.telephony.Connection.class);
        doReturn("5551212").when(c.mMockRadioConnection).getAddress();
        doReturn("5551212").when(newMockRadioConnection).getAddress();
        doReturn(Call.State.DIALING).when(newMockRadioConnection).getState();
        c.onOriginalConnectionRedialed(newMockRadioConnection);
        verify(newMockRadioConnection).hangup();
    }

    @Test
    public void testSetupDtmfOnly() {
        TestTelephonyConnection c = new TestTelephonyConnection();
        c.setIsImsConnection(true);
        // Enable D2D comms.
        when(c.mMockResources.getBoolean(eq(
                R.bool.config_use_device_to_device_communication))).thenReturn(true);
        // But carrier disables transports.  Womp.
        c.getCarrierConfigBundle().putBoolean(
                CarrierConfigManager.KEY_SUPPORTS_DEVICE_TO_DEVICE_COMMUNICATION_USING_DTMF_BOOL,
                true);
        c.getCarrierConfigBundle().putBoolean(
                CarrierConfigManager.KEY_SUPPORTS_DEVICE_TO_DEVICE_COMMUNICATION_USING_RTP_BOOL,
                false);
        c.setOriginalConnection(mImsPhoneConnection);
        assertNotNull(c.getCommunicator());
        assertEquals(1, c.getCommunicator().getTransportProtocols().size());
        assertTrue(c.getCommunicator().getTransportProtocols()
                .stream().anyMatch(p -> p instanceof DtmfTransport));
    }

    @Test
    public void testCodecInIms() {
        TestTelephonyConnection c = new TestTelephonyConnection();
        c.setIsImsConnection(true);
        c.updateState();
        Bundle extras = c.getExtras();
        int codec = extras.getInt(Connection.EXTRA_AUDIO_CODEC, Connection.AUDIO_CODEC_NONE);
        assertEquals(codec, Connection.AUDIO_CODEC_AMR);
    }

    @Test
    public void testConferenceNotSupportedForDownGradedVideoCall() {
        TestTelephonyConnection c = new TestTelephonyConnection();
        c.setIsImsConnection(true);
        c.setIsVideoCall(false);
        c.setWasVideoCall(true);
        c.setDownGradeVideoCall(true);
        c.refreshConferenceSupported();
        assertFalse(c.isConferenceSupported());
        c.setDownGradeVideoCall(false);
        c.refreshConferenceSupported();
        assertTrue(c.isConferenceSupported());
    }

    /**
     * Tests to ensure that the presence of an ImsExternalConnection does not cause a crash in
     * TelephonyConnection due to an illegal cast.
     */
    @Test
    public void testImsExternalConnectionOnRefreshConference() {
        TestTelephonyConnection c = new TestTelephonyConnection();
        c.setIsImsConnection(true);
        c.setIsImsExternalConnection(true);
        try {
            c.refreshConferenceSupported();
        } catch (ClassCastException e) {
            fail("refreshConferenceSupported threw ClassCastException");
        }
    }

    /**
     * Tests TelephonyConnection#getCarrierConfig never returns a null given all cases that can
     * cause a potential null.
     */
    @Test
    public void testGetCarrierConfigBehaviorWithNull() throws Exception {
        TestTelephonyConnectionSimple c = new TestTelephonyConnectionSimple();

        // case: return a valid carrier config (good case)
        when(c.mPhoneGlobals.getCarrierConfigForSubId(c.getPhone().getSubId())).
                thenReturn(CarrierConfigManager.getDefaultConfig());
        assertNotNull(c.getCarrierConfig());

        // case: PhoneGlobals.getInstance().getCarrierConfigForSubId(int) returns null
        when(c.mPhoneGlobals.getCarrierConfigForSubId(c.getPhone().getSubId()))
                .thenReturn(null);
        assertNotNull(c.getCarrierConfig());

        // case: phone is null
        c.setMockPhone(null);
        assertNull(c.getPhone());
        assertNotNull(c.getCarrierConfig());
    }

    /**
     * Tests the behavior of TelephonyConnection#isRttMergeSupported(@NonNull PersistableBundle).
     * Note, the function should be able to handle an empty PersistableBundle and should NEVER
     * receive a null object as denoted in by @NonNull annotation.
     */
    @Test
    public void testIsRttMergeSupportedBehavior() {
        TestTelephonyConnection c = new TestTelephonyConnection();
        //  ensure isRttMergeSupported(PersistableBundle) does not throw NPE when given an Empty PB
        assertFalse(c.isRttMergeSupported(new PersistableBundle()));

        // simulate the passing situation
        c.getCarrierConfigBundle().putBoolean(
                CarrierConfigManager.KEY_ALLOW_MERGING_RTT_CALLS_BOOL,
                true);
        assertTrue(c.isRttMergeSupported(c.getCarrierConfig()));
    }

}
