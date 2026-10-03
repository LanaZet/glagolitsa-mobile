// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberCallAudioPermissionController(): CallAudioPermissionController =
    remember {
        object : CallAudioPermissionController {
            override fun runWithPermission(
                onGranted: () -> Unit,
                onDenied: (String) -> Unit,
            ) {
                onGranted()
            }
        }
    }
