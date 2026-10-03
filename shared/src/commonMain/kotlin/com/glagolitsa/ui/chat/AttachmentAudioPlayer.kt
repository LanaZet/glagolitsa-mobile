// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.runtime.Composable
import com.glagolitsa.repository.AttachmentPreview

data class AttachmentAudioPlayerState(
    val playingMessageId: String? = null,
    val durationMs: Long = 0L,
    val errorMessage: String? = null,
) {
    fun isPlaying(preview: AttachmentPreview): Boolean = playingMessageId == preview.messageId
}

interface AttachmentAudioPlayerController {
    val state: AttachmentAudioPlayerState
    fun play(preview: AttachmentPreview)
    fun stop()
}

@Composable
expect fun rememberAttachmentAudioPlayerController(): AttachmentAudioPlayerController
