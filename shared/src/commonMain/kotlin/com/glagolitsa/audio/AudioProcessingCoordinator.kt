// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.audio

import com.glagolitsa.model.CallAudioConfig
import com.glagolitsa.platform.installationSeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/**
 * Process-wide snapshot of NS preferences, A/B arm and device capability.
 */
object AudioProcessingCoordinator {
    private val _settings = MutableStateFlow(AudioProcessingUserSettings())
    val settings: StateFlow<AudioProcessingUserSettings> = _settings.asStateFlow()

    @Volatile
    var capability: DeviceAudioCapability = DeviceAudioCapability.Unknown
        private set

    fun hydrate(settings: AudioProcessingUserSettings, capability: DeviceAudioCapability = probeDeviceAudioCapability()) {
        val arm = CallNsExperiment.assignArm(
            installationSeed = installationSeed(),
            persisted = settings.experimentArm,
        )
        _settings.value = settings.copy(experimentArm = arm)
        this.capability = capability
    }

    fun updateSettings(settings: AudioProcessingUserSettings) {
        val arm = settings.experimentArm ?: _settings.value.experimentArm
        _settings.value = settings.copy(experimentArm = arm)
    }

    fun disableEnhancedExperiment() {
        _settings.value = _settings.value.copy(experimentDisabled = true)
    }

    fun currentSettings(): AudioProcessingUserSettings = _settings.value

    fun resolveCall(server: CallAudioConfig = CallAudioConfig()): CallAudioProcessingPlan {
        val snap = _settings.value
        return CallAudioProcessingPolicy.resolve(
            preference = snap.callPreference,
            server = server,
            capability = capability,
            experimentArm = snap.experimentArm ?: CallNsArm.Base,
            experimentDisabled = snap.experimentDisabled,
            deepFilterAvailable = DeepFilterNetRegistry.available,
        )
    }

    fun resolveVoice(): VoiceMessageProcessingPlan {
        val snap = _settings.value
        return VoiceMessageProcessingPolicy.resolve(
            preference = snap.voicePreference,
            deepFilterAvailable = DeepFilterNetRegistry.available,
        )
    }

    fun enhancePcm(
        pcm: ShortArray,
        sampleRateHz: Int,
        plan: VoiceMessageProcessingPlan,
    ): Pair<ShortArray, VoiceEnhancerEngine> {
        if (!plan.shouldProcess || pcm.isEmpty()) {
            return pcm to VoiceEnhancerEngine.None
        }
        if (plan.appliedEngine == VoiceEnhancerEngine.DeepFilterNet) {
            val dfn = DeepFilterNetRegistry.enhanceOrNull(pcm, sampleRateHz)
            if (dfn != null) return dfn to VoiceEnhancerEngine.DeepFilterNet
        }
        val hq = plan.requestedEngine == VoiceEnhancerEngine.DeepFilterNet
        val processed = SpectralNoiseSuppressor(sampleRateHz, highQuality = hq).enhance(pcm)
        return processed to VoiceEnhancerEngine.Rnnoise
    }
}

fun AudioProcessingUserSettings.toStorageMap(): Map<String, String> = mapOf(
    AudioProcessingSettingsKeys.CALL_PREFERENCE to callPreference.storageKey,
    AudioProcessingSettingsKeys.VOICE_PREFERENCE to voicePreference.storageKey,
    AudioProcessingSettingsKeys.EXPERIMENT_ARM to (experimentArm?.storageKey ?: ""),
    AudioProcessingSettingsKeys.EXPERIMENT_DISABLED to if (experimentDisabled) "1" else "0",
)

fun audioProcessingSettingsFrom(values: Map<String, String>): AudioProcessingUserSettings =
    AudioProcessingUserSettings(
        callPreference = CallNoisePreference.fromStorageKey(
            values[AudioProcessingSettingsKeys.CALL_PREFERENCE],
        ),
        voicePreference = VoiceNoisePreference.fromStorageKey(
            values[AudioProcessingSettingsKeys.VOICE_PREFERENCE],
        ),
        experimentArm = CallNsArm.fromStorageKey(values[AudioProcessingSettingsKeys.EXPERIMENT_ARM]),
        experimentDisabled = values[AudioProcessingSettingsKeys.EXPERIMENT_DISABLED] == "1",
    )
