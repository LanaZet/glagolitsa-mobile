// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.media.AttachmentKind
import com.glagolitsa.model.Message
import com.glagolitsa.repository.AttachmentPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoicePlaybackTest {
    @Test
    fun progress_isZeroWhenDurationMissing() {
        assertEquals(0f, voicePlaybackProgress(1_000L, 0L))
        assertEquals(0f, voicePlaybackProgress(1_000L, -4L))
    }

    @Test
    fun progress_clampsToUnitInterval() {
        assertEquals(0f, voicePlaybackProgress(-20L, 1_000L))
        assertEquals(0.25f, voicePlaybackProgress(250L, 1_000L))
        assertEquals(1f, voicePlaybackProgress(2_000L, 1_000L))
    }

    @Test
    fun duration_formatsAsMinutesAndSeconds() {
        assertEquals("0:00", formatMediaDuration(-1L))
        assertEquals("0:05", formatMediaDuration(5_900L))
        assertEquals("2:03", formatMediaDuration(123_000L))
    }

    @Test
    fun animatedAmplitude_keepsBaseWhenIdle() {
        assertEquals(0.4f, voiceBarAnimatedAmplitude(0.4f, playing = false, phase = 0.3f, index = 4))
    }

    @Test
    fun animatedAmplitude_movesWithPhaseWhenPlaying() {
        val low = voiceBarAnimatedAmplitude(0.6f, playing = true, phase = 0.75f, index = 0)
        val high = voiceBarAnimatedAmplitude(0.6f, playing = true, phase = 0.25f, index = 0)
        assertTrue(low < high)
        assertTrue(low >= 0.08f)
        assertTrue(high <= 1f)
    }

    @Test
    fun playedBars_followProgress() {
        assertFalse(voiceBarIsPlayed(0, 10, 0f))
        assertTrue(voiceBarIsPlayed(0, 10, 0.1f))
        assertFalse(voiceBarIsPlayed(8, 10, 0.1f))
        assertTrue(voiceBarIsPlayed(9, 10, 1f))
    }

    @Test
    fun playingMatch_acceptsPreviewIdOrPendingId() {
        val preview = AttachmentPreview(
            messageId = "pending-1",
            bytes = ByteArray(0),
            fileName = "voice.m4a",
            mimeType = "audio/mp4",
            kind = AttachmentKind.VOICE,
        )
        val message = Message(
            id = "server-1",
            chat_id = "chat",
            sender_id = "me",
            body = "",
            created_at = "2026-08-13T10:00:00Z",
            pending_id = "pending-1",
        )
        assertTrue(isAttachmentPlaying(preview, message, "pending-1"))
        assertTrue(isAttachmentPlaying(preview, message, "server-1"))
        assertFalse(isAttachmentPlaying(preview, message, "other"))
        assertFalse(isAttachmentPlaying(preview, message, null))
    }
}
