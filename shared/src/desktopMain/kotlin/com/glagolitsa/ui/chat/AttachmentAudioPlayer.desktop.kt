// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.glagolitsa.repository.AttachmentPreview

@Composable
actual fun rememberAttachmentAudioPlayerController(): AttachmentAudioPlayerController =
    remember {
        object : AttachmentAudioPlayerController {
            override val state = AttachmentAudioPlayerState(
                errorMessage = "Воспроизведение голосовых пока доступно только на Android",
            )

            override fun play(preview: AttachmentPreview) = Unit
            override fun stop() = Unit
        }
    }
