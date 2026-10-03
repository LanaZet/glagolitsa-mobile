// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.call

/**
 * Architectural window for future live captions/translation (stage 12).
 * Not implemented — no STT/LLM provider, no transcript storage.
 *
 * Captions must never break E2EE: default OFF; cloud modes require explicit
 * consent and only send the enabling participant's own audio.
 */
enum class CallCaptionMode {
    OFF,
    LOCAL_CAPTIONS,
    CLIENT_CLOUD_OWN_AUDIO,
    SERVER_AGENT_TRANSLATOR,
}

/**
 * Reserved interface so [CallMediaEngine] is not rewritten when captions land.
 * Implementations must not control ICE/room lifecycle.
 */
interface CallCaptionTranslator {
    val mode: CallCaptionMode
    suspend fun start(mode: CallCaptionMode)
    suspend fun stop()
}

/** No-op placeholder until product enables captions. */
class NoopCallCaptionTranslator : CallCaptionTranslator {
    override val mode: CallCaptionMode = CallCaptionMode.OFF
    override suspend fun start(mode: CallCaptionMode) = Unit
    override suspend fun stop() = Unit
}

/**
 * Encrypted caption segment contract (ciphertext only on the wire).
 * Do not add plaintext transcript fields to call history.
 */
data class CallCaptionSegmentEnvelope(
    val type: String = "call_caption_segment",
    val callId: String,
    val speakerParticipantId: String,
    val segmentId: String,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val isFinal: Boolean = false,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val textCiphertext: String,
    val modelFamily: String? = null,
)
