// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.runtime.Composable

/**
 * OS notification permission + master switch (Signal: settings first, then system dialog).
 */
interface NotificationPermissionController {
    val notificationsEnabled: Boolean
    fun refresh()
    fun request(onResult: (granted: Boolean) -> Unit = {})
    fun openSystemSettings()
}

@Composable
expect fun rememberNotificationPermissionController(): NotificationPermissionController
