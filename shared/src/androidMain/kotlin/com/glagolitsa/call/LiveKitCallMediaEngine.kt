// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.call

import android.content.Context
import com.twilio.audioswitch.AudioDevice
import com.glagolitsa.audio.CallAudioProcessingPlan
import com.glagolitsa.audio.CallCaptureAudioProcessor
import com.glagolitsa.audio.VoiceEnhancerEngine
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioProcessorOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.e2ee.BaseKeyProvider
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrackOptions
import com.glagolitsa.util.encodeBase64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android LiveKit audio-first media engine.
 * Tokens and keys must never be logged.
 */
class LiveKitCallMediaEngine(
    private val appContext: Context,
) : CallMediaEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var room: Room? = null
    private var eventsJob: Job? = null
    private var audioSwitchHandler: AudioSwitchHandler? = null
    private var speakerPreference: Boolean = false
    private var audioPlan: CallAudioProcessingPlan = CallAudioProcessingPlan.BASE

    private val _state = MutableStateFlow(CallMediaState())
    override val state: StateFlow<CallMediaState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<CallMediaEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<CallMediaEvent> = _events.asSharedFlow()

    private val _stats = MutableStateFlow<CallMediaStatsSnapshot?>(null)
    override val stats: StateFlow<CallMediaStatsSnapshot?> = _stats.asStateFlow()

    override suspend fun connect(options: CallMediaConnectOptions) {
        disconnectInternal(keepState = false)
        audioPlan = options.audioProcessing
        _state.value = CallMediaState(connection = CallMediaConnectionState.CONNECTING)
        try {
            withContext(Dispatchers.Main) {
                LiveKit.init(appContext.applicationContext)
            }
            val roomOptions = RoomOptions(
                adaptiveStream = true,
                dynacast = true,
                e2eeOptions = options.e2eeKey?.let { key ->
                    val keyProvider = BaseKeyProvider()
                    keyProvider.setSharedKey(key.encodeBase64())
                    E2EEOptions(keyProvider = keyProvider)
                },
            )
            val audioHandler = createAudioSwitchHandler()
            audioSwitchHandler = audioHandler
            val extraProcessor = captureProcessor(audioPlan)
            val liveKitOverrides = LiveKitOverrides(
                audioOptions = AudioOptions(
                    audioHandler = audioHandler,
                    audioProcessorOptions = extraProcessor?.let { processor ->
                        AudioProcessorOptions(capturePostProcessor = processor)
                    },
                ),
            )
            val r = withContext(Dispatchers.Main) {
                LiveKit.create(
                    appContext.applicationContext,
                    options = roomOptions,
                    overrides = liveKitOverrides,
                )
            }
            room = r
            eventsJob = scope.launch {
                r.events.collect { event ->
                    when (event) {
                        is RoomEvent.Connected -> {
                            _state.value = _state.value.copy(
                                connection = CallMediaConnectionState.CONNECTED,
                            )
                            applyAudioRoutePreference()
                            _events.tryEmit(CallMediaEvent.RoomConnected)
                        }
                        is RoomEvent.Reconnecting -> {
                            _state.value = _state.value.copy(
                                connection = CallMediaConnectionState.RECONNECTING,
                            )
                            _events.tryEmit(CallMediaEvent.Reconnecting)
                        }
                        is RoomEvent.Reconnected -> {
                            _state.value = _state.value.copy(
                                connection = CallMediaConnectionState.CONNECTED,
                            )
                        }
                        is RoomEvent.Disconnected -> {
                            _state.value = _state.value.copy(
                                connection = CallMediaConnectionState.DISCONNECTED,
                                microphonePublished = false,
                                mediaPathConfirmed = false,
                            )
                            _events.tryEmit(CallMediaEvent.Disconnected)
                        }
                        is RoomEvent.FailedToConnect -> {
                            val msg = event.error?.message ?: "failed to connect"
                            _state.value = CallMediaState(
                                connection = CallMediaConnectionState.FAILED,
                                errorMessage = msg,
                            )
                            _events.tryEmit(CallMediaEvent.Failed(msg))
                        }
                        is RoomEvent.TrackPublished -> {
                            if (event.publication.kind.name.equals("AUDIO", ignoreCase = true) ||
                                event.publication.source.name.contains("MICROPHONE", ignoreCase = true)
                            ) {
                                _state.value = _state.value.copy(
                                    microphonePublished = true,
                                    mediaPathConfirmed = true,
                                )
                                _events.tryEmit(CallMediaEvent.MicrophonePublished)
                                _events.tryEmit(CallMediaEvent.MediaPathConfirmed)
                            }
                        }
                        is RoomEvent.ConnectionQualityChanged -> {
                            _stats.value = CallMediaStatsSnapshot(
                                connectionQuality = event.quality.name,
                                collectedAtMs = System.currentTimeMillis(),
                            )
                        }
                        else -> Unit
                    }
                }
            }
            // Do not log options.token.
            withContext(Dispatchers.IO) {
                r.connect(url = options.url, token = options.token)
            }
            withContext(Dispatchers.Main) {
                applyAudioRoutePreference()
            }
        } catch (t: Throwable) {
            val msg = t.message ?: "connect failed"
            _state.value = CallMediaState(
                connection = CallMediaConnectionState.FAILED,
                errorMessage = msg,
            )
            _events.tryEmit(CallMediaEvent.Failed(msg))
            throw t
        }
    }

    override suspend fun publishMicrophone() {
        val r = room ?: error("not connected")
        withContext(Dispatchers.Main) {
            r.localParticipant.audioTrackCaptureDefaults = localAudioOptions(audioPlan)
            r.localParticipant.setMicrophoneEnabled(true)
            // Some SDK versions publish asynchronously; mark intent immediately.
            _state.value = _state.value.copy(
                microphonePublished = true,
                microphoneMuted = false,
            )
            // Confirm media path after successful enable for audio-only stage 5.
            if (!_state.value.mediaPathConfirmed) {
                _state.value = _state.value.copy(mediaPathConfirmed = true)
                _events.tryEmit(CallMediaEvent.MediaPathConfirmed)
            }
            _events.tryEmit(CallMediaEvent.MicrophonePublished)
        }
    }

    override suspend fun setMicrophoneMuted(muted: Boolean) {
        val r = room ?: return
        withContext(Dispatchers.Main) {
            r.localParticipant.setMicrophoneEnabled(!muted)
            _state.value = _state.value.copy(microphoneMuted = muted)
        }
    }

    override suspend fun setSpeaker(speakerOn: Boolean) {
        speakerPreference = speakerOn
        withContext(Dispatchers.Main) {
            if (!applyAudioRoutePreference()) {
                _state.value = _state.value.copy(speakerOn = speakerOn)
            }
        }
    }

    override suspend fun disconnect() {
        disconnectInternal(keepState = true)
    }

    private suspend fun disconnectInternal(keepState: Boolean) {
        eventsJob?.cancel()
        eventsJob = null
        val r = room
        room = null
        if (r != null) {
            withContext(Dispatchers.Main) {
                runCatching { r.disconnect() }
                runCatching { r.release() }
            }
        }
        audioSwitchHandler = null
        speakerPreference = false
        if (keepState) {
            _state.value = CallMediaState(connection = CallMediaConnectionState.DISCONNECTED)
            _events.tryEmit(CallMediaEvent.Disconnected)
        }
    }

    override fun release() {
        scope.launch { disconnectInternal(keepState = true) }
    }

    private fun localAudioOptions(plan: CallAudioProcessingPlan): LocalAudioTrackOptions =
        LocalAudioTrackOptions(
            noiseSuppression = plan.noiseSuppression,
            echoCancellation = plan.echoCancellation,
            autoGainControl = plan.autoGainControl,
            highPassFilter = plan.highPassFilter,
            typingNoiseDetection = plan.noiseSuppression,
        )

    private fun captureProcessor(plan: CallAudioProcessingPlan): CallCaptureAudioProcessor? {
        if (plan.extraEngine == VoiceEnhancerEngine.None) return null
        return CallCaptureAudioProcessor(
            engine = plan.extraEngine,
            highQuality = plan.extraEngine == VoiceEnhancerEngine.DeepFilterNet,
        )
    }

    private fun createAudioSwitchHandler(): AudioSwitchHandler =
        AudioSwitchHandler(appContext.applicationContext).apply {
            loggingEnabled = false
            preferredDeviceList = CallPreferredDeviceList
            registerAudioDeviceChangeListener { availableDevices, selectedDevice ->
                val target = targetAudioDevice(availableDevices, speakerPreference)
                if (target != null && !sameAudioDeviceType(selectedDevice, target)) {
                    selectDevice(target)
                }
                _state.value = _state.value.copy(
                    speakerOn = (target ?: selectedDevice) is AudioDevice.Speakerphone,
                )
            }
        }

    private fun applyAudioRoutePreference(): Boolean {
        val handler = audioSwitchHandler ?: room?.audioSwitchHandler ?: return false
        val target = targetAudioDevice(handler.availableAudioDevices, speakerPreference) ?: return false
        handler.selectDevice(target)
        _state.value = _state.value.copy(speakerOn = target is AudioDevice.Speakerphone)
        return true
    }

    private fun targetAudioDevice(
        availableDevices: List<AudioDevice>,
        speakerOn: Boolean,
    ): AudioDevice? {
        if (speakerOn) {
            return availableDevices.firstOrNull { it is AudioDevice.Speakerphone }
                ?: availableDevices.firstOrNull()
        }
        return availableDevices.firstOrNull { it !is AudioDevice.Speakerphone }
            ?: availableDevices.firstOrNull()
    }

    private fun sameAudioDeviceType(
        selectedDevice: AudioDevice?,
        targetDevice: AudioDevice,
    ): Boolean = selectedDevice != null && selectedDevice::class == targetDevice::class

    private companion object {
        val CallPreferredDeviceList: List<Class<out AudioDevice>> = listOf(
            AudioDevice.BluetoothHeadset::class.java,
            AudioDevice.WiredHeadset::class.java,
            AudioDevice.Earpiece::class.java,
            AudioDevice.Speakerphone::class.java,
        )
    }
}
