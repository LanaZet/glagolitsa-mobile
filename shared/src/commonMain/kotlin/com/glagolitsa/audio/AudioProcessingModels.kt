// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.audio

/**
 * Client-side voice processing stack.
 *
 * Calls: WebRTC APM (AEC+NS+AGC) is always the base. RNNoise-class enhancement
 * is optional and A/B-tested. DeepFilterNet is HQ-only on strong devices.
 *
 * Voice messages: prefer DeepFilterNet 3 when a backend is registered; RNNoise-class
 * spectral enhancement is the lightweight fallback. The original is kept locally
 * until the user sends the chosen file (E2EE encrypts that chosen result).
 */
enum class VoiceEnhancerEngine {
    None,
    WebRtcApm,
    Rnnoise,
    DeepFilterNet,
}

enum class CallNoisePreference {
    Auto,
    Off,
    Base,
    Enhanced,
    HighQuality,
    ;

    val storageKey: String
        get() = when (this) {
            Auto -> "auto"
            Off -> "off"
            Base -> "base"
            Enhanced -> "enhanced"
            HighQuality -> "hq"
        }

    companion object {
        fun fromStorageKey(value: String?): CallNoisePreference =
            entries.firstOrNull { it.storageKey == value } ?: Auto
    }
}

enum class VoiceNoisePreference {
    Auto,
    Off,
    Light,
    HighQuality,
    ;

    val storageKey: String
        get() = when (this) {
            Auto -> "auto"
            Off -> "off"
            Light -> "light"
            HighQuality -> "hq"
        }

    companion object {
        fun fromStorageKey(value: String?): VoiceNoisePreference =
            entries.firstOrNull { it.storageKey == value } ?: Auto
    }
}

enum class CallNsArm {
    Base,
    Enhanced,
    ;

    val storageKey: String
        get() = when (this) {
            Base -> "base"
            Enhanced -> "enhanced"
        }

    companion object {
        fun fromStorageKey(value: String?): CallNsArm? =
            entries.firstOrNull { it.storageKey == value }
    }
}

enum class VoiceSendVariant {
    Original,
    Processed,
}

data class AudioProcessingUserSettings(
    val callPreference: CallNoisePreference = CallNoisePreference.Auto,
    val voicePreference: VoiceNoisePreference = VoiceNoisePreference.Auto,
    val experimentArm: CallNsArm? = null,
    val experimentDisabled: Boolean = false,
)

data class CallAudioProcessingPlan(
    val echoCancellation: Boolean,
    val noiseSuppression: Boolean,
    val autoGainControl: Boolean,
    val highPassFilter: Boolean,
    val extraEngine: VoiceEnhancerEngine,
    val experimentArm: CallNsArm? = null,
    val reason: String,
) {
    val apmEnabled: Boolean
        get() = echoCancellation || noiseSuppression || autoGainControl

    companion object {
        val BASE = CallAudioProcessingPlan(
            echoCancellation = true,
            noiseSuppression = true,
            autoGainControl = true,
            highPassFilter = true,
            extraEngine = VoiceEnhancerEngine.None,
            reason = "base_apm",
        )
        val OFF = CallAudioProcessingPlan(
            echoCancellation = false,
            noiseSuppression = false,
            autoGainControl = false,
            highPassFilter = false,
            extraEngine = VoiceEnhancerEngine.None,
            reason = "off",
        )
    }
}

data class VoiceMessageProcessingPlan(
    val requestedEngine: VoiceEnhancerEngine,
    val appliedEngine: VoiceEnhancerEngine,
    val reason: String,
) {
    val shouldProcess: Boolean
        get() = appliedEngine != VoiceEnhancerEngine.None
}

data class DeviceAudioCapability(
    val cpuCores: Int = 0,
    val ramMb: Int = 0,
    val batteryPercent: Int? = null,
    val batteryCharging: Boolean = false,
    val lowRamDevice: Boolean = false,
    val lastHqFrameMs: Float? = null,
) {
    val strongForHqCalls: Boolean
        get() {
            if (lowRamDevice) return false
            if (cpuCores < HQ_MIN_CORES) return false
            if (ramMb < HQ_MIN_RAM_MB) return false
            val battery = batteryPercent
            if (!batteryCharging && battery != null && battery < HQ_MIN_BATTERY_PERCENT) return false
            val frameMs = lastHqFrameMs
            if (frameMs != null && frameMs > HQ_MAX_FRAME_MS) return false
            return true
        }

    companion object {
        const val HQ_MIN_CORES = 6
        const val HQ_MIN_RAM_MB = 4096
        const val HQ_MIN_BATTERY_PERCENT = 25
        const val HQ_MAX_FRAME_MS = 8f
        val Unknown = DeviceAudioCapability()
    }
}

object AudioProcessingSettingsKeys {
    const val CALL_PREFERENCE = "audio.call_ns_mode"
    const val VOICE_PREFERENCE = "audio.voice_ns_mode"
    const val EXPERIMENT_ARM = "audio.call_ns_experiment"
    const val EXPERIMENT_DISABLED = "audio.call_ns_experiment_disabled"
}
