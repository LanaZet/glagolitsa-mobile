// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

actual fun installationSeed(): String {
    val dir = Path.of(System.getProperty("user.home"), ".glagolitsa")
    val file = dir.resolve("installation_id")
    return runCatching {
        if (!dir.exists()) {
            Files.createDirectories(dir)
        }
        if (!file.exists()) {
            file.writeText(UUID.randomUUID().toString())
        }
        file.readText().trim().ifBlank { UUID.randomUUID().toString() }
    }.getOrElse { "desktop-unknown" }
}
