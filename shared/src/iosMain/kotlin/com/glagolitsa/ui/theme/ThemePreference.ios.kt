// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.theme

import platform.Foundation.NSUserDefaults

private const val PREFS_KEY = "glagolitsa.app.theme"

internal actual fun loadPersistedThemeKey(): String? =
    NSUserDefaults.standardUserDefaults.stringForKey(PREFS_KEY)?.takeIf { it.isNotBlank() }

internal actual fun persistThemeKey(value: String) {
    NSUserDefaults.standardUserDefaults.setObject(value, forKey = PREFS_KEY)
}
