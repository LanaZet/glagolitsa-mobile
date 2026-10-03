// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.theme

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal actual fun loadPersistedThemeKey(): String? {
    val file = themeFile()
    return runCatching {
        if (!file.exists()) return@runCatching null
        file.readText().trim().ifBlank { null }
    }.getOrNull()
}

internal actual fun persistThemeKey(value: String) {
    runCatching {
        val dir = themeDir()
        if (!dir.exists()) {
            Files.createDirectories(dir)
        }
        themeFile().writeText(value)
    }
}

private fun themeDir(): Path = Path.of(System.getProperty("user.home"), ".glagolitsa")

private fun themeFile(): Path = themeDir().resolve("theme")
