// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.theme

import android.content.Context
import com.glagolitsa.platform.AppLifecycle

private const val PREFS_NAME = "glagolitsa.theme"
private const val PREFS_KEY = "app.theme"

internal actual fun loadPersistedThemeKey(): String? {
    val context = AppLifecycle.applicationContextOrNull() ?: return null
    return themePrefs(context).getString(PREFS_KEY, null)?.takeIf { it.isNotBlank() }
}

internal actual fun persistThemeKey(value: String) {
    val context = AppLifecycle.applicationContextOrNull() ?: return
    themePrefs(context).edit().putString(PREFS_KEY, value).apply()
}

private fun themePrefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
