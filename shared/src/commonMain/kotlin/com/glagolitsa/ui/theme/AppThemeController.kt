// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppThemeMode(val storageKey: String) {
    Dark("dark"),
    Light("light"),
    ;

    val isDark: Boolean
        get() = this == Dark

    companion object {
        const val SETTINGS_KEY = "app.theme"

        fun fromStorage(value: String?): AppThemeMode =
            entries.firstOrNull { it.storageKey == value } ?: Dark
    }
}

/**
 * Process-wide theme slot. Compose reads [mode] during color token lookup,
 * so screens recompose when the profile toggle flips.
 *
 * [restorePersisted] / [persistCurrent] use device prefs, not the account DB.
 */
object AppThemeController {
    var mode by mutableStateOf(AppThemeMode.Dark)
        private set
    internal var persistedRestored = false

    val isDark: Boolean
        get() = mode.isDark

    fun applyMode(next: AppThemeMode) {
        if (mode != next) mode = next
    }

    fun setDark(dark: Boolean) {
        applyMode(if (dark) AppThemeMode.Dark else AppThemeMode.Light)
        persistCurrent()
    }

    fun toggle() {
        setDark(!isDark)
    }

    fun restorePersisted() {
        if (persistedRestored) return
        persistedRestored = true
        applyMode(AppThemeMode.fromStorage(themePreferenceReader()))
    }

    fun persistCurrent() {
        themePreferenceWriter(mode.storageKey)
    }
}

internal var themePreferenceReader: () -> String? = { loadPersistedThemeKey() }
internal var themePreferenceWriter: (String) -> Unit = { persistThemeKey(it) }

internal fun resetThemePreferenceIo() {
    themePreferenceReader = { loadPersistedThemeKey() }
    themePreferenceWriter = { persistThemeKey(it) }
    AppThemeController.persistedRestored = false
}
