// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberNotificationPermissionController(): NotificationPermissionController =
    remember {
        object : NotificationPermissionController {
            override val notificationsEnabled: Boolean = true
            override fun refresh() = Unit
            override fun request(onResult: (Boolean) -> Unit) = onResult(true)
            override fun openSystemSettings() = Unit
        }
    }
