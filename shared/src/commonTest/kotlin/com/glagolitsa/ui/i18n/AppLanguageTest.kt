// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.i18n

import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageTest {
    @Test
    fun restoresKnownLanguageAndFallsBackToRussian() {
        assertEquals(AppLanguage.English, AppLanguage.fromStorage("en"))
        assertEquals(AppLanguage.Russian, AppLanguage.fromStorage("ru"))
        assertEquals(AppLanguage.Russian, AppLanguage.fromStorage("unknown"))
        assertEquals(AppLanguage.Russian, AppLanguage.fromStorage(null))
    }

    @Test
    fun selectsTextForCurrentLanguage() {
        assertEquals("Русский", AppLanguage.Russian.select("Русский", "English"))
        assertEquals("English", AppLanguage.English.select("Русский", "English"))
    }
}
