// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver = createDriver(namespace = null)

    actual fun createDriver(namespace: String?): SqlDriver {
        val name = databaseName(namespace)
        val driver = NativeSqliteDriver(
            schema = GlagolitsaDatabase.Schema,
            name = name,
        )
        DatabaseSchemaRepair.ensureUpToDate(driver)
        return driver
    }

    private fun databaseName(namespace: String?): String {
        val normalized = namespace?.takeIf { it.isNotBlank() } ?: return "glagolitsa-ios.db"
        val safe = normalized.replace("/", "_").replace(":", "_").take(48)
        return "glagolitsa-$safe-ios.db"
    }
}
