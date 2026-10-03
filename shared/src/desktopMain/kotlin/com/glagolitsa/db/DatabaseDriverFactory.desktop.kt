// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.security.MessageDigest

actual class DatabaseDriverFactory private constructor(
    private val inMemory: Boolean,
) {
    constructor() : this(inMemory = false)

    /** In-memory SQLite — для desktopTest и локальных прогонов без файла. */
    fun companionInMemory(): DatabaseDriverFactory = DatabaseDriverFactory(inMemory = true)

    actual fun createDriver(): SqlDriver = createDriver(namespace = null)

    actual fun createDriver(namespace: String?): SqlDriver {
        if (inMemory) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            GlagolitsaDatabase.Schema.create(driver)
            DatabaseSchemaRepair.ensureUpToDate(driver)
            return driver
        }
        val dbFile = File(System.getProperty("user.home"), ".glagolitsa/${databaseName(namespace)}")
        dbFile.parentFile?.mkdirs()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        val currentVersion = driver.readUserVersion()
        if (currentVersion == 0L) {
            // Fresh file: create schema and stamp version so re-open does not re-CREATE.
            GlagolitsaDatabase.Schema.create(driver)
            driver.setUserVersion(GlagolitsaDatabase.Schema.version)
        } else if (currentVersion < GlagolitsaDatabase.Schema.version) {
            GlagolitsaDatabase.Schema.migrate(
                driver,
                oldVersion = currentVersion,
                newVersion = GlagolitsaDatabase.Schema.version,
            )
            driver.setUserVersion(GlagolitsaDatabase.Schema.version)
        }
        DatabaseSchemaRepair.ensureUpToDate(driver)
        return driver
    }

    private fun databaseName(namespace: String?): String {
        val normalized = namespace?.takeIf { it.isNotBlank() } ?: return "glagolitsa-desktop.db"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
            .take(24)
        return "glagolitsa-$digest-desktop.db"
    }

    private fun SqlDriver.readUserVersion(): Long {
        var version = 0L
        executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor ->
                if (cursor.next().value) {
                    version = cursor.getLong(0) ?: 0L
                }
                QueryResult.Unit
            },
            parameters = 0,
        )
        return version
    }

    private fun SqlDriver.setUserVersion(version: Long) {
        execute(
            identifier = null,
            sql = "PRAGMA user_version = $version",
            parameters = 0,
        )
    }
}
