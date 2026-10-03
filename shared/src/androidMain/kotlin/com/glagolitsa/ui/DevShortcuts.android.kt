// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import com.glagolitsa.api.defaultBaseUrl
import com.glagolitsa.platform.AppRuntimeInfo
import com.glagolitsa.shared.BuildConfig

actual fun devShortcutsEnabled(): Boolean = AppRuntimeInfo.devShortcutsEnabled

/** Always surface the real resolved API URL so public vs local is obvious on the login screen. */
actual fun devApiBaseUrlLabel(): String? =
    defaultBaseUrl()

actual fun devShortcutAccounts(): List<DevShortcutAccount> {
    if (!devShortcutsEnabled()) return emptyList()
    return listOf(
        DevShortcutAccount(
            username = BuildConfig.DEV_PRIMARY_USERNAME,
            password = BuildConfig.DEV_PRIMARY_PASSWORD,
            label = BuildConfig.DEV_PRIMARY_LABEL,
        ),
        DevShortcutAccount(
            username = BuildConfig.DEV_SECONDARY_USERNAME,
            password = BuildConfig.DEV_SECONDARY_PASSWORD,
            label = BuildConfig.DEV_SECONDARY_LABEL,
        ),
    ).filter { it.username.isNotBlank() && it.password.isNotBlank() }
}
