// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallQualityPolicyTest {
    @Test
    fun critical_goesEmergency() {
        val p = CallQualityPolicy()
        val d = p.evaluate(CallQualitySample(noRtpProgress = true))
        assertEquals(CallNetworkMode.EMERGENCY_VOICE, d.mode)
        assertTrue(d.forceVideoOff)
    }

    @Test
    fun poor_twice_forcesVoiceOnly() {
        val p = CallQualityPolicy(mode = CallNetworkMode.VIDEO_LOW)
        p.evaluate(CallQualitySample(connectionQuality = "Poor", rttMs = 700))
        val d = p.evaluate(CallQualitySample(connectionQuality = "Poor", rttMs = 700))
        assertEquals(CallNetworkMode.VOICE_ONLY, d.mode)
        assertTrue(d.forceVideoOff)
    }

    @Test
    fun good_streak_canOfferVideo() {
        val p = CallQualityPolicy()
        repeat(15) {
            p.evaluate(
                CallQualitySample(
                    connectionQuality = "Excellent",
                    rttMs = 80,
                    packetLossPercent = 0.5f,
                    availableOutgoingKbps = 800,
                ),
            )
        }
        val d = p.evaluate(
            CallQualitySample(
                connectionQuality = "Excellent",
                rttMs = 80,
                packetLossPercent = 0.5f,
                availableOutgoingKbps = 800,
            ),
        )
        assertTrue(d.allowVideoOffer)
    }

    @Test
    fun diagnostics_redactedBuckets() {
        val snap = CallQualitySample(rttMs = 120, packetLossPercent = 2f, candidateType = "relay")
            .toDiagnostics("call-1", CallNetworkMode.VOICE_ONLY)
        assertEquals("100-200", snap.rttBucket)
        assertFalse(snap.callId.contains("token"))
    }
}
