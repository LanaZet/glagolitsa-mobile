// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppLanguage(val storageKey: String, val displayName: String) {
    Russian("ru", "Русский"),
    English("en", "English");

    fun select(russian: String, english: String): String =
        if (this == English) english else russian

    companion object {
        const val SETTINGS_KEY = "app_language"

        fun fromStorage(value: String?): AppLanguage =
            entries.firstOrNull { it.storageKey == value } ?: Russian
    }
}

object AppLanguageController {
    var language by mutableStateOf(AppLanguage.Russian)
        private set

    fun applyLanguage(value: AppLanguage) {
        if (language != value) language = value
    }

    fun text(russian: String, english: String): String =
        language.select(russian, english)
}

fun tr(russian: String, english: String): String = AppLanguageController.text(russian, english)
