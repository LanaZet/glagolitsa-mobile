// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import java.security.MessageDigest

actual class DatabaseDriverFactory(
    private val context: Context,
) {
    actual fun createDriver(): SqlDriver = createDriver(namespace = null)

    actual fun createDriver(namespace: String?): SqlDriver {
        SQLiteDatabase.loadLibs(context)
        val passphrase = AndroidDatabasePassphraseStore.getOrCreate(context)
        val databaseName = databaseName(namespace)
        migratePlaintextDatabaseIfNeeded(databaseName)
        val factory = SupportFactory(passphrase)
        val driver = AndroidSqliteDriver(
            schema = GlagolitsaDatabase.Schema,
            context = context,
            name = databaseName,
            factory = factory,
        )
        DatabaseSchemaRepair.ensureUpToDate(driver)
        return driver
    }

    /**
     * Старый plaintext SQLite несовместим с SQLCipher — удаляем, кэш подтянется с сервера.
     */
    private fun migratePlaintextDatabaseIfNeeded(databaseName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "$KEY_SQLCIPHER_ENABLED:$databaseName"
        if (prefs.getBoolean(key, false)) {
            return
        }
        if (databaseName == DATABASE_NAME && prefs.getBoolean(KEY_SQLCIPHER_ENABLED, false)) {
            prefs.edit().putBoolean(key, true).apply()
            return
        }
        context.deleteDatabase(databaseName)
        prefs.edit().putBoolean(key, true).apply()
    }

    private fun databaseName(namespace: String?): String {
        val normalized = namespace?.takeIf { it.isNotBlank() } ?: return DATABASE_NAME
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
            .take(24)
        return "glagolitsa-$digest.db"
    }

    private companion object {
        const val DATABASE_NAME = "glagolitsa.db"
        const val PREFS_NAME = "glagolitsa_db_key_meta"
        const val KEY_SQLCIPHER_ENABLED = "sqlcipher_enabled"
    }
}
