// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.glagolitsa.platform.iosRunOnMain
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted

@Composable
actual fun rememberCallAudioPermissionController(): CallAudioPermissionController =
    remember { IosCallAudioPermissionController() }

private class IosCallAudioPermissionController : CallAudioPermissionController {
    override fun runWithPermission(
        onGranted: () -> Unit,
        onDenied: (String) -> Unit,
    ) {
        val session = AVAudioSession.sharedInstance()
        when (session.recordPermission) {
            AVAudioSessionRecordPermissionGranted -> onGranted()
            AVAudioSessionRecordPermissionDenied -> onDenied("Нет доступа к микрофону")
            else -> session.requestRecordPermission { granted ->
                iosRunOnMain {
                    if (granted) onGranted() else onDenied("Нет доступа к микрофону")
                }
            }
        }
    }
}
