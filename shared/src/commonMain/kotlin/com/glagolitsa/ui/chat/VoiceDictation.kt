// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.runtime.Composable

enum class VoiceDictationStatus {
    Idle,
    Listening,
    Processing,
    Unsupported,
}

data class VoiceDictationState(
    val status: VoiceDictationStatus = VoiceDictationStatus.Idle,
    val partialText: String = "",
    val errorMessage: String? = null,
) {
    val isListening: Boolean
        get() = status == VoiceDictationStatus.Listening

    val isProcessing: Boolean
        get() = status == VoiceDictationStatus.Processing

    val isActive: Boolean
        get() = isListening || isProcessing
}

interface VoiceDictationController {
    val state: VoiceDictationState
    fun start()
    fun stop()
    fun cancel()
}

@Composable
expect fun rememberVoiceDictationController(
    onResult: (String) -> Unit,
    onError: (String) -> Unit = {},
): VoiceDictationController

fun appendVoiceDictationText(draft: String, transcript: String): String {
    val clean = transcript.trim()
    if (clean.isBlank()) return draft
    if (draft.isBlank()) return clean
    return if (draft.last().isWhitespace()) draft + clean else "$draft $clean"
}
