// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

/** Быстрый вход — только для внутренних emulator/dev сборок, не для реальных пользователей. */
data class DevShortcutAccount(
    val username: String,
    val password: String,
    val label: String,
)

expect fun devShortcutsEnabled(): Boolean

expect fun devShortcutAccounts(): List<DevShortcutAccount>

/** URL API, зашитый в dev/emulator сборку — для подсказки на экране входа. */
expect fun devApiBaseUrlLabel(): String?
