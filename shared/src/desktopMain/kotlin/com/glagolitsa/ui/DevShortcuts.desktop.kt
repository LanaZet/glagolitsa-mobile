// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import com.glagolitsa.platform.AppRuntimeInfo

// Desktop is a design/dev sandbox; shortcuts stay on, but never pretend to be a phone public build.
actual fun devShortcutsEnabled(): Boolean = AppRuntimeInfo.devShortcutsEnabled

actual fun devApiBaseUrlLabel(): String? = "http://127.0.0.1:8080"

actual fun devShortcutAccounts(): List<DevShortcutAccount> =
    listOf(
        DevShortcutAccount("Marco", "marco123", "Marco"),
        DevShortcutAccount("Polo", "polo123", "Polo"),
    )
