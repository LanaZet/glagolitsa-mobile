// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter

@Composable
actual fun rememberNotificationPermissionController(): NotificationPermissionController =
    remember { IosNotificationPermissionController() }

private class IosNotificationPermissionController : NotificationPermissionController {
    private var enabledState by mutableStateOf(false)

    override val notificationsEnabled: Boolean get() = enabledState

    init {
        refresh()
    }

    override fun refresh() {
        UNUserNotificationCenter.currentNotificationCenter()
            .getNotificationSettingsWithCompletionHandler { settings ->
                val status = settings?.authorizationStatus
                enabledState = status == UNAuthorizationStatusAuthorized ||
                    status == UNAuthorizationStatusProvisional ||
                    status == UNAuthorizationStatusEphemeral
            }
    }

    override fun request(onResult: (Boolean) -> Unit) {
        val options = UNAuthorizationOptionAlert or
            UNAuthorizationOptionSound or
            UNAuthorizationOptionBadge
        UNUserNotificationCenter.currentNotificationCenter()
            .requestAuthorizationWithOptions(options) { granted, _ ->
                enabledState = granted
                onResult(granted)
            }
    }

    override fun openSystemSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        val app = UIApplication.sharedApplication
        if (app.canOpenURL(url)) {
            app.openURL(url)
        }
    }
}
