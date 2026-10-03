// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.call

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform media implementation for calls (LiveKit on Android).
 * Control plane stays in [com.glagolitsa.repository.CallController].
 *
 * Never logs tokens, TURN credentials, media keys, or peer IPs.
 */
interface CallMediaEngine {
    val state: StateFlow<CallMediaState>
    val events: SharedFlow<CallMediaEvent>
    val stats: StateFlow<CallMediaStatsSnapshot?>

    suspend fun connect(options: CallMediaConnectOptions)
    suspend fun publishMicrophone()
    suspend fun setMicrophoneMuted(muted: Boolean)
    suspend fun setSpeaker(speakerOn: Boolean)
    suspend fun disconnect()
    fun release()
}

data class CallMediaConnectOptions(
    val token: String,
    val url: String,
    val roomName: String,
    val participantId: String,
    /** Base64 or raw key material when E2EE is ready (stage 6). */
    val e2eeKey: ByteArray? = null,
    val audioOnly: Boolean = true,
    val audioProcessing: com.glagolitsa.audio.CallAudioProcessingPlan =
        com.glagolitsa.audio.CallAudioProcessingPlan.BASE,
)

enum class CallMediaConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED,
    DISCONNECTED,
}

data class CallMediaState(
    val connection: CallMediaConnectionState = CallMediaConnectionState.IDLE,
    val microphonePublished: Boolean = false,
    val microphoneMuted: Boolean = false,
    val speakerOn: Boolean = false,
    val mediaPathConfirmed: Boolean = false,
    val errorMessage: String? = null,
)

sealed class CallMediaEvent {
    data object RoomConnected : CallMediaEvent()
    data object MicrophonePublished : CallMediaEvent()
    data object MediaPathConfirmed : CallMediaEvent()
    data object Reconnecting : CallMediaEvent()
    data object Disconnected : CallMediaEvent()
    data class Failed(val message: String) : CallMediaEvent()
}

/**
 * Coarse stats for quality policy (stage 7). No peer IPs or ICE candidates.
 */
data class CallMediaStatsSnapshot(
    val rttMs: Int? = null,
    val packetLossPercent: Float? = null,
    val jitterMs: Float? = null,
    val candidateType: String? = null,
    val candidateProtocol: String? = null,
    val connectionQuality: String? = null,
    val collectedAtMs: Long = 0L,
)

/** No-op engine for desktop/tests and when LiveKit flag is off. */
class NoopCallMediaEngine : CallMediaEngine {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(CallMediaState())
    override val state: StateFlow<CallMediaState> = _state
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<CallMediaEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<CallMediaEvent> = _events
    private val _stats = kotlinx.coroutines.flow.MutableStateFlow<CallMediaStatsSnapshot?>(null)
    override val stats: StateFlow<CallMediaStatsSnapshot?> = _stats

    override suspend fun connect(options: CallMediaConnectOptions) {
        _state.value = CallMediaState(
            connection = CallMediaConnectionState.CONNECTED,
            mediaPathConfirmed = false,
            errorMessage = "Media engine not available on this platform",
        )
        _events.tryEmit(CallMediaEvent.Failed("Media engine not available"))
    }

    override suspend fun publishMicrophone() = Unit
    override suspend fun setMicrophoneMuted(muted: Boolean) {
        _state.value = _state.value.copy(microphoneMuted = muted)
    }

    override suspend fun setSpeaker(speakerOn: Boolean) {
        _state.value = _state.value.copy(speakerOn = speakerOn)
    }

    override suspend fun disconnect() {
        _state.value = CallMediaState(connection = CallMediaConnectionState.DISCONNECTED)
        _events.tryEmit(CallMediaEvent.Disconnected)
    }

    override fun release() = Unit
}

expect fun createCallMediaEngine(): CallMediaEngine
