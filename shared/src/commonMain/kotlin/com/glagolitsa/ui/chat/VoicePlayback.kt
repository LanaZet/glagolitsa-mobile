// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.Message
import com.glagolitsa.repository.AttachmentPreview
import kotlin.math.PI
import kotlin.math.sin

internal fun voicePlaybackProgress(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

internal fun formatMediaDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/**
 * Extra amplitude 0..1 for a waveform bar while the clip is playing.
 * Idle playback must keep the recorded (or deterministic) shape unchanged.
 */
internal fun voiceBarPlaybackPulse(index: Int, phase: Float): Float {
    val angle = (phase * TWO_PI) + index * 0.55f
    return (0.5f + 0.5f * sin(angle)).toFloat()
}

internal fun voiceBarAnimatedAmplitude(
    base: Float,
    playing: Boolean,
    phase: Float,
    index: Int,
): Float {
    val clamped = base.coerceIn(0f, 1f)
    if (!playing) return clamped
    val pulse = voiceBarPlaybackPulse(index, phase)
    return (clamped * (0.70f + 0.48f * pulse)).coerceIn(0.08f, 1f)
}

internal fun voiceBarIsPlayed(index: Int, count: Int, progress: Float): Boolean {
    if (count <= 0 || progress <= 0f) return false
    if (progress >= 1f) return true
    return (index + 0.5f) / count <= progress
}

internal fun isAttachmentPlaying(
    preview: AttachmentPreview,
    message: Message,
    playingMessageId: String?,
): Boolean {
    if (playingMessageId.isNullOrBlank()) return false
    return preview.messageId == playingMessageId ||
        message.id == playingMessageId ||
        message.pending_id == playingMessageId
}

private const val TWO_PI = (PI * 2.0).toFloat()
