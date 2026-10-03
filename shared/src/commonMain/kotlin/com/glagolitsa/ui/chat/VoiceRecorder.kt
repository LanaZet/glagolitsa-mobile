// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.runtime.Composable
import com.glagolitsa.audio.VoiceRecordingDraft

enum class VoiceRecorderStatus {
    Idle,
    Recording,
    Processing,
    Unsupported,
}

data class VoiceRecorderState(
    val status: VoiceRecorderStatus = VoiceRecorderStatus.Idle,
    val startedAtEpochMs: Long = 0L,
    val elapsedMs: Long = 0L,
    val errorMessage: String? = null,
) {
    val isRecording: Boolean
        get() = status == VoiceRecorderStatus.Recording

    val isProcessing: Boolean
        get() = status == VoiceRecorderStatus.Processing

    val isActive: Boolean
        get() = isRecording || isProcessing
}

interface VoiceRecorderController {
    val state: VoiceRecorderState
    fun start()
    fun stop()
    fun cancel()
}

@Composable
expect fun rememberVoiceRecorderController(
    onDraft: (VoiceRecordingDraft) -> Unit,
    onError: (String) -> Unit = {},
): VoiceRecorderController
