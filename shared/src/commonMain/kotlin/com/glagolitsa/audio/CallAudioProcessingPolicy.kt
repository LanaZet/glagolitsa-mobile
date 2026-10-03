// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.audio

import com.glagolitsa.model.CallAudioConfig

/**
 * Resolves the call capture stack: native WebRTC APM first, optional extra NS second.
 */
object CallAudioProcessingPolicy {

    fun resolve(
        preference: CallNoisePreference,
        server: CallAudioConfig = CallAudioConfig(),
        capability: DeviceAudioCapability = DeviceAudioCapability.Unknown,
        experimentArm: CallNsArm = CallNsArm.Base,
        experimentDisabled: Boolean = false,
        deepFilterAvailable: Boolean = false,
    ): CallAudioProcessingPlan {
        if (preference == CallNoisePreference.Off) {
            return CallAudioProcessingPlan.OFF
        }

        val echo = server.echo_cancellation
        val ns = server.noise_suppression
        val agc = server.auto_gain_control
        if (!echo && !ns && !agc) {
            return CallAudioProcessingPlan.OFF.copy(reason = "server_apm_off")
        }

        val extra = extraEngine(
            preference = preference,
            capability = capability,
            experimentArm = experimentArm,
            experimentDisabled = experimentDisabled,
            deepFilterAvailable = deepFilterAvailable,
        )
        return CallAudioProcessingPlan(
            echoCancellation = echo,
            noiseSuppression = ns,
            autoGainControl = agc,
            highPassFilter = ns || echo,
            extraEngine = extra.engine,
            experimentArm = extra.arm,
            reason = extra.reason,
        )
    }

    private fun extraEngine(
        preference: CallNoisePreference,
        capability: DeviceAudioCapability,
        experimentArm: CallNsArm,
        experimentDisabled: Boolean,
        deepFilterAvailable: Boolean,
    ): Extra {
        when (preference) {
            CallNoisePreference.Off,
            CallNoisePreference.Base,
            -> return Extra(VoiceEnhancerEngine.None, null, "base_apm")
            CallNoisePreference.Enhanced ->
                return Extra(VoiceEnhancerEngine.Rnnoise, CallNsArm.Enhanced, "user_enhanced")
            CallNoisePreference.HighQuality -> {
                if (capability.strongForHqCalls && deepFilterAvailable) {
                    return Extra(VoiceEnhancerEngine.DeepFilterNet, null, "user_hq_dfn")
                }
                return Extra(VoiceEnhancerEngine.Rnnoise, CallNsArm.Enhanced, "hq_fallback_rnnoise")
            }
            CallNoisePreference.Auto -> {
                if (capability.strongForHqCalls && deepFilterAvailable) {
                    return Extra(VoiceEnhancerEngine.DeepFilterNet, null, "auto_hq_dfn")
                }
                if (experimentDisabled || experimentArm == CallNsArm.Base) {
                    return Extra(VoiceEnhancerEngine.None, CallNsArm.Base, "ab_base")
                }
                return Extra(VoiceEnhancerEngine.Rnnoise, CallNsArm.Enhanced, "ab_enhanced")
            }
        }
    }

    private data class Extra(
        val engine: VoiceEnhancerEngine,
        val arm: CallNsArm?,
        val reason: String,
    )
}
