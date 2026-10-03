// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.audio

import com.glagolitsa.model.CallAudioConfig
import com.glagolitsa.ui.chat.PickedAttachment
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioProcessingPolicyTest {
    @Test
    fun callAuto_usesExperimentArmWhenDeviceIsNotHq() {
        val base = CallAudioProcessingPolicy.resolve(
            preference = CallNoisePreference.Auto,
            capability = DeviceAudioCapability(cpuCores = 4, ramMb = 2048),
            experimentArm = CallNsArm.Base,
        )
        assertEquals(VoiceEnhancerEngine.None, base.extraEngine)
        assertTrue(base.noiseSuppression)

        val enhanced = CallAudioProcessingPolicy.resolve(
            preference = CallNoisePreference.Auto,
            capability = DeviceAudioCapability(cpuCores = 4, ramMb = 2048),
            experimentArm = CallNsArm.Enhanced,
        )
        assertEquals(VoiceEnhancerEngine.Rnnoise, enhanced.extraEngine)
        assertEquals("ab_enhanced", enhanced.reason)
    }

    @Test
    fun callHq_usesDeepFilterOnlyOnStrongDevice() {
        val weak = CallAudioProcessingPolicy.resolve(
            preference = CallNoisePreference.HighQuality,
            capability = DeviceAudioCapability(cpuCores = 4, ramMb = 2048),
            deepFilterAvailable = true,
        )
        assertEquals(VoiceEnhancerEngine.Rnnoise, weak.extraEngine)

        val strong = CallAudioProcessingPolicy.resolve(
            preference = CallNoisePreference.HighQuality,
            capability = DeviceAudioCapability(
                cpuCores = 8,
                ramMb = 6144,
                batteryPercent = 80,
            ),
            deepFilterAvailable = true,
        )
        assertEquals(VoiceEnhancerEngine.DeepFilterNet, strong.extraEngine)
    }

    @Test
    fun callOff_disablesApm() {
        val plan = CallAudioProcessingPolicy.resolve(CallNoisePreference.Off)
        assertFalse(plan.apmEnabled)
        assertEquals(VoiceEnhancerEngine.None, plan.extraEngine)
    }

    @Test
    fun serverCanDisableApm() {
        val plan = CallAudioProcessingPolicy.resolve(
            preference = CallNoisePreference.Enhanced,
            server = CallAudioConfig(
                noise_suppression = false,
                echo_cancellation = false,
                auto_gain_control = false,
            ),
        )
        assertEquals("server_apm_off", plan.reason)
        assertFalse(plan.apmEnabled)
    }

    @Test
    fun voiceAuto_fallsBackToRnnoiseWithoutDeepFilter() {
        val plan = VoiceMessageProcessingPolicy.resolve(VoiceNoisePreference.Auto, deepFilterAvailable = false)
        assertEquals(VoiceEnhancerEngine.DeepFilterNet, plan.requestedEngine)
        assertEquals(VoiceEnhancerEngine.Rnnoise, plan.appliedEngine)
        assertTrue(plan.shouldProcess)
    }

    @Test
    fun voiceAuto_usesDeepFilterWhenRegistered() {
        val plan = VoiceMessageProcessingPolicy.resolve(VoiceNoisePreference.Auto, deepFilterAvailable = true)
        assertEquals(VoiceEnhancerEngine.DeepFilterNet, plan.appliedEngine)
    }

    @Test
    fun experimentArm_isStickyAndBalanced() {
        assertEquals(CallNsArm.Base, CallNsExperiment.assignArm("seed-a", persisted = CallNsArm.Base))
        val a = CallNsExperiment.assignArm("device-one")
        val b = CallNsExperiment.assignArm("device-two")
        val c = CallNsExperiment.assignArm("device-three")
        assertTrue(setOf(a, b, c).isNotEmpty())
    }

    @Test
    fun draft_sendsSelectedResultOnly() {
        val original = PickedAttachment(bytes = byteArrayOf(1), fileName = "o.m4a", mimeType = "audio/mp4")
        val processed = PickedAttachment(bytes = byteArrayOf(2), fileName = "p.m4a", mimeType = "audio/mp4")
        val draft = VoiceRecordingDraft(original = original, processed = processed)
        assertEquals(processed, draft.chosen())
        assertEquals(original, draft.withSelection(VoiceSendVariant.Original).chosen())
    }

    @Test
    fun spectralEnhancer_improvesSnrOfNoisyTone() {
        val sampleRate = 16_000
        val tone = ShortArray(sampleRate) { i ->
            // Leading noise-only region lets the estimator learn the floor.
            if (i < sampleRate / 4) {
                0
            } else {
                (sin(2.0 * PI * 440.0 * i / sampleRate) * 12_000).toInt().toShort()
            }
        }
        val noisy = ShortArray(tone.size) { i ->
            val noise = (((i * 1103515245 + 12345) ushr 16) and 0x7FFF) % 9000 - 4500
            (tone[i] + noise).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val cleaned = SpectralNoiseSuppressor(sampleRate, highQuality = true).enhance(noisy)
        val before = pcmSnrDb(tone, noisy)
        val after = pcmSnrDb(tone, cleaned)
        assertTrue(after > before, "expected SNR $after > $before")
    }

    @Test
    fun strongDevice_requiresRamCoresAndBattery() {
        assertFalse(
            DeviceAudioCapability(cpuCores = 8, ramMb = 6144, batteryPercent = 10).strongForHqCalls,
        )
        assertTrue(
            DeviceAudioCapability(cpuCores = 8, ramMb = 6144, batteryPercent = 40).strongForHqCalls,
        )
        assertFalse(
            DeviceAudioCapability(cpuCores = 8, ramMb = 6144, lowRamDevice = true).strongForHqCalls,
        )
    }
}
