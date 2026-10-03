// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallFeaturePolicyTest {

    @Test
    fun canStartAudio_requiresLiveKitOrAudioOnly() {
        assertFalse(CallFeaturePolicy.canStartAudioCall(emptyMap()))
        assertFalse(
            CallFeaturePolicy.canStartAudioCall(
                mapOf(CallFeatureKeys.AUDIO to true),
            ),
        )
        assertTrue(
            CallFeaturePolicy.canStartAudioCall(
                mapOf(
                    CallFeatureKeys.AUDIO to true,
                    CallFeatureKeys.RTC_LIVEKIT to true,
                ),
            ),
        )
        assertTrue(
            CallFeaturePolicy.canStartAudioCall(
                mapOf(
                    CallFeatureKeys.AUDIO to true,
                    CallFeatureKeys.AUDIO_ONLY to true,
                ),
            ),
        )
    }

    @Test
    fun killSwitch_hidesStartCallWhenLiveKitAndAudioOnlyOff() {
        val features = mapOf(
            CallFeatureKeys.AUDIO to true,
            CallFeatureKeys.VIDEO to true,
            CallFeatureKeys.RTC_LIVEKIT to false,
            CallFeatureKeys.AUDIO_ONLY to false,
        )
        assertFalse(CallFeaturePolicy.canStartAudioCall(features))
        assertFalse(CallFeaturePolicy.canStartVideoCall(features))
        assertNotNull(CallFeaturePolicy.startCallBlockedReason(features, CallType.AUDIO))
    }

    @Test
    fun audioOnly_withoutVideo() {
        val features = mapOf(
            CallFeatureKeys.AUDIO to true,
            CallFeatureKeys.VIDEO to false,
            CallFeatureKeys.AUDIO_ONLY to true,
            CallFeatureKeys.RTC_LIVEKIT to true,
        )
        assertTrue(CallFeaturePolicy.canStartAudioCall(features))
        assertFalse(CallFeaturePolicy.canStartVideoCall(features))
        assertNull(CallFeaturePolicy.startCallBlockedReason(features, CallType.AUDIO))
        assertNotNull(CallFeaturePolicy.startCallBlockedReason(features, CallType.VIDEO))
    }

    @Test
    fun unknownRequestGate_defaultsOn() {
        assertTrue(CallFeaturePolicy.isUnknownRequestGateEnabled(emptyMap()))
        assertFalse(
            CallFeaturePolicy.isUnknownRequestGateEnabled(
                mapOf(CallFeatureKeys.UNKNOWN_REQUEST_GATE to false),
            ),
        )
    }

    @Test
    fun adaptiveAndGroupFlags_defaultOff() {
        assertFalse(CallFeaturePolicy.isAdaptiveVideoEnabled(emptyMap()))
        assertFalse(CallFeaturePolicy.isGroupReadyApiEnabled(emptyMap()))
        assertFalse(CallFeaturePolicy.isRouteHintsEnabled(emptyMap()))
    }
}
