// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
actual fun rememberVoiceDictationController(
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
): VoiceDictationController =
    remember(onError) {
        object : VoiceDictationController {
            override var state by mutableStateOf(
                VoiceDictationState(status = VoiceDictationStatus.Unsupported),
            )

            override fun start() {
                val message = "Диктовка на iOS пока не подключена"
                state = VoiceDictationState(
                    status = VoiceDictationStatus.Unsupported,
                    errorMessage = message,
                )
                onError(message)
            }

            override fun stop() = Unit
            override fun cancel() = Unit
        }
    }
