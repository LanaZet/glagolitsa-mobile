// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.theme

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AppThemeControllerTest {
    @AfterTest
    fun resetTheme() {
        resetThemePreferenceIo()
        AppThemeController.applyMode(AppThemeMode.Dark)
    }

    @Test
    fun fromStorage_defaultsToDark() {
        assertEquals(AppThemeMode.Dark, AppThemeMode.fromStorage(null))
        assertEquals(AppThemeMode.Dark, AppThemeMode.fromStorage(""))
        assertEquals(AppThemeMode.Dark, AppThemeMode.fromStorage("unknown"))
        assertEquals(AppThemeMode.Light, AppThemeMode.fromStorage("light"))
    }

    @Test
    fun toggle_switchesSlots() {
        AppThemeController.applyMode(AppThemeMode.Dark)
        assertTrue(AppThemeController.isDark)
        assertEquals(GlagolitsaPalettes.Dark.background950, GlagolitsaColors.Background950)
        AppThemeController.applyMode(AppThemeMode.Light)
        assertFalse(AppThemeController.isDark)
        assertEquals(GlagolitsaPalettes.Light.background950, GlagolitsaColors.Background950)
        assertNotEquals(GlagolitsaPalettes.Dark.background950, GlagolitsaPalettes.Light.background950)
        assertNotEquals(GlagolitsaPalettes.Dark.textPrimary, GlagolitsaPalettes.Light.textPrimary)
        assertEquals(GlagolitsaPalettes.Light.chatWallpaperTop, GlagolitsaColors.ChatWallpaperTop)
        assertEquals(GlagolitsaPalettes.Light.chatBubbleOwnMid, GlagolitsaColors.ChatBubbleOwnMid)
        assertNotEquals(GlagolitsaPalettes.Dark.chatWallpaperTop, GlagolitsaPalettes.Light.chatWallpaperTop)
        assertNotEquals(GlagolitsaPalettes.Dark.chatChromeTop, GlagolitsaPalettes.Light.chatChromeTop)
    }

    @Test
    fun restorePersisted_appliesStoredLight() {
        themePreferenceReader = { "light" }
        AppThemeController.restorePersisted()
        assertFalse(AppThemeController.isDark)
        assertEquals(AppThemeMode.Light, AppThemeController.mode)
    }

    @Test
    fun restorePersisted_readsOnlyOnce() {
        var reads = 0
        themePreferenceReader = {
            reads++
            "light"
        }
        AppThemeController.restorePersisted()
        AppThemeController.applyMode(AppThemeMode.Dark)
        AppThemeController.restorePersisted()
        assertEquals(1, reads)
        assertTrue(AppThemeController.isDark)
    }

    @Test
    fun persistCurrent_writesStorageKey() {
        var written: String? = null
        themePreferenceWriter = { written = it }
        AppThemeController.applyMode(AppThemeMode.Light)
        AppThemeController.persistCurrent()
        assertEquals(AppThemeMode.Light.storageKey, written)
    }

    @Test
    fun setDark_persistsLight() {
        var written: String? = null
        themePreferenceWriter = { written = it }
        AppThemeController.setDark(false)
        assertFalse(AppThemeController.isDark)
        assertEquals(AppThemeMode.Light.storageKey, written)
    }
}
