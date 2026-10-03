// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.audio

/**
 * Voice notes are offline: latency is not critical, so Auto/HQ ask for DeepFilterNet 3
 * and fall back to the RNNoise-class enhancer when the HQ backend is missing.
 */
object VoiceMessageProcessingPolicy {

    fun resolve(
        preference: VoiceNoisePreference,
        deepFilterAvailable: Boolean = false,
    ): VoiceMessageProcessingPlan {
        return when (preference) {
            VoiceNoisePreference.Off -> VoiceMessageProcessingPlan(
                requestedEngine = VoiceEnhancerEngine.None,
                appliedEngine = VoiceEnhancerEngine.None,
                reason = "off",
            )
            VoiceNoisePreference.Light -> VoiceMessageProcessingPlan(
                requestedEngine = VoiceEnhancerEngine.Rnnoise,
                appliedEngine = VoiceEnhancerEngine.Rnnoise,
                reason = "user_light",
            )
            VoiceNoisePreference.HighQuality,
            VoiceNoisePreference.Auto,
            -> {
                if (deepFilterAvailable) {
                    VoiceMessageProcessingPlan(
                        requestedEngine = VoiceEnhancerEngine.DeepFilterNet,
                        appliedEngine = VoiceEnhancerEngine.DeepFilterNet,
                        reason = if (preference == VoiceNoisePreference.Auto) "auto_dfn" else "user_hq_dfn",
                    )
                } else {
                    VoiceMessageProcessingPlan(
                        requestedEngine = VoiceEnhancerEngine.DeepFilterNet,
                        appliedEngine = VoiceEnhancerEngine.Rnnoise,
                        reason = "dfn_fallback_rnnoise",
                    )
                }
            }
        }
    }
}
