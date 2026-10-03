// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.glagolitsa.audio.VoiceRecordingDraft

@Composable
actual fun rememberVoiceRecorderController(
    onDraft: (VoiceRecordingDraft) -> Unit,
    onError: (String) -> Unit,
): VoiceRecorderController =
    remember(onError) {
        object : VoiceRecorderController {
            override val state = VoiceRecorderState(
                status = VoiceRecorderStatus.Unsupported,
                errorMessage = "Голосовые сообщения пока доступны только на Android",
            )

            override fun start() {
                onError(state.errorMessage.orEmpty())
            }

            override fun stop() = Unit
            override fun cancel() = Unit
        }
    }
