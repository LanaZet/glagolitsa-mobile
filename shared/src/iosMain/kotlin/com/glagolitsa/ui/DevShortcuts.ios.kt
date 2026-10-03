// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import com.glagolitsa.platform.AppRuntimeInfo

actual fun devShortcutsEnabled(): Boolean = AppRuntimeInfo.devShortcutsEnabled

actual fun devApiBaseUrlLabel(): String? = null

actual fun devShortcutAccounts(): List<DevShortcutAccount> = emptyList()
