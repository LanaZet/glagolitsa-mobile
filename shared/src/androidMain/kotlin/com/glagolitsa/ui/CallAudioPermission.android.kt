// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberCallAudioPermissionController(): CallAudioPermissionController {
    val context = LocalContext.current
    val controller = remember(context) {
        AndroidCallAudioPermissionController(context.applicationContext)
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        controller.onPermissionResult(granted)
    }
    controller.requestPermission = {
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
    return controller
}

private class AndroidCallAudioPermissionController(
    private val context: Context,
) : CallAudioPermissionController {
    var requestPermission: (() -> Unit)? = null
    private var pendingGranted: (() -> Unit)? = null
    private var pendingDenied: ((String) -> Unit)? = null

    override fun runWithPermission(
        onGranted: () -> Unit,
        onDenied: (String) -> Unit,
    ) {
        if (hasRecordAudioPermission()) {
            onGranted()
            return
        }
        pendingGranted = onGranted
        pendingDenied = onDenied
        requestPermission?.invoke() ?: run {
            denyPending()
            clearPending()
        }
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            pendingGranted?.invoke()
        } else {
            denyPending()
        }
        clearPending()
    }

    private fun denyPending() {
        pendingDenied?.invoke("Нет доступа к микрофону")
    }

    private fun clearPending() {
        pendingGranted = null
        pendingDenied = null
    }

    private fun hasRecordAudioPermission(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            true
        } else {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
}
