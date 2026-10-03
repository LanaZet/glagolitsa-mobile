// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.runtime.Composable

interface CallAudioPermissionController {
    fun runWithPermission(
        onGranted: () -> Unit,
        onDenied: (String) -> Unit,
    )
}

@Composable
expect fun rememberCallAudioPermissionController(): CallAudioPermissionController
