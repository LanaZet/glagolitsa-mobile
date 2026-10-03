// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Дополняет схему на устройствах, где user_version уже актуален, но часть миграций
 * фактически не применилась (типичный случай после смены нумерации .sqm-файлов).
 */
object DatabaseSchemaRepair {
    fun ensureUpToDate(driver: SqlDriver) {
        ensureOutboxJobsTable(driver)
        ensureAppSettingsTable(driver)
        ensureAppSessionColumns(driver)
        ensureMessagesColumns(driver)
        ensureLocalAttachmentsTable(driver)
        ensureChatsChannelColumns(driver)
    }

    private fun ensureChatsChannelColumns(driver: SqlDriver) {
        if (!tableExists(driver, "chats")) return
        val columns = readTableColumns(driver, "chats")
        addColumnIfMissing(driver, "chats", columns, "description", "TEXT")
        addColumnIfMissing(driver, "chats", columns, "visibility", "TEXT")
        addColumnIfMissing(driver, "chats", columns, "slug", "TEXT")
        addColumnIfMissing(driver, "chats", columns, "encryption", "TEXT")
        addColumnIfMissing(driver, "chats", columns, "creator_id", "TEXT")
        addColumnIfMissing(driver, "chats", columns, "avatar_url", "TEXT")
    }

    private fun ensureOutboxJobsTable(driver: SqlDriver) {
        if (tableExists(driver, "outbox_jobs")) return
        driver.execute(
            null,
            """
            CREATE TABLE outbox_jobs (
                id TEXT NOT NULL PRIMARY KEY,
                job_type TEXT NOT NULL,
                chat_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                attempts INTEGER NOT NULL DEFAULT 0,
                next_attempt_at TEXT,
                created_at TEXT NOT NULL,
                last_error TEXT
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "CREATE INDEX IF NOT EXISTS outbox_jobs_status_next_attempt ON outbox_jobs(status, next_attempt_at)",
            0,
        )
    }

    private fun ensureAppSettingsTable(driver: SqlDriver) {
        if (tableExists(driver, "app_settings")) return
        driver.execute(
            null,
            """
            CREATE TABLE IF NOT EXISTS app_settings (
                setting_key TEXT NOT NULL PRIMARY KEY,
                setting_value TEXT NOT NULL
            )
            """.trimIndent(),
            0,
        )
    }

    private fun ensureAppSessionColumns(driver: SqlDriver) {
        if (!tableExists(driver, "app_session")) return
        val columns = readTableColumns(driver, "app_session")
        addColumnIfMissing(driver, "app_session", columns, "display_name", "TEXT")
        addColumnIfMissing(driver, "app_session", columns, "status", "TEXT")
        addColumnIfMissing(driver, "app_session", columns, "bio", "TEXT")
        addColumnIfMissing(driver, "app_session", columns, "avatar_url", "TEXT")
        addColumnIfMissing(driver, "app_session", columns, "presence", "TEXT")
        addColumnIfMissing(driver, "app_session", columns, "nickname", "TEXT")
        addColumnIfMissing(driver, "app_session", columns, "position", "TEXT")
    }

    private fun ensureMessagesColumns(driver: SqlDriver) {
        if (!tableExists(driver, "messages")) return
        val columns = readTableColumns(driver, "messages")
        addColumnIfMissing(driver, "messages", columns, "reply_to_message_id", "TEXT")
        addColumnIfMissing(driver, "messages", columns, "reply_preview_sender_id", "TEXT")
        addColumnIfMissing(driver, "messages", columns, "reply_preview_body", "TEXT")
        addColumnIfMissing(driver, "messages", columns, "thread_root_id", "TEXT")
        addColumnIfMissing(driver, "messages", columns, "thread_parent_id", "TEXT")
        if ("visibility" !in columns) {
            driver.execute(
                null,
                "ALTER TABLE messages ADD COLUMN visibility TEXT NOT NULL DEFAULT 'main'",
                0,
            )
        }
        if ("thread_reply_count" !in columns) {
            driver.execute(
                null,
                "ALTER TABLE messages ADD COLUMN thread_reply_count INTEGER NOT NULL DEFAULT 0",
                0,
            )
        }
        addColumnIfMissing(driver, "messages", columns, "last_thread_reply_at", "TEXT")
        addColumnIfMissing(driver, "messages", columns, "last_thread_reply_sender_id", "TEXT")
        addColumnIfMissing(driver, "messages", columns, "expires_at", "TEXT")
        addColumnIfMissing(driver, "messages", columns, "envelope_type", "INTEGER")
        addColumnIfMissing(driver, "messages", columns, "ciphertext", "TEXT")
        addColumnIfMissing(driver, "messages", columns, "sender_device_id", "TEXT")
        driver.execute(
            null,
            "CREATE INDEX IF NOT EXISTS messages_chat_visibility_created_at ON messages(chat_id, visibility, created_at)",
            0,
        )
        driver.execute(
            null,
            "CREATE INDEX IF NOT EXISTS messages_thread_root_created_at ON messages(thread_root_id, created_at)",
            0,
        )
        driver.execute(
            null,
            "CREATE INDEX IF NOT EXISTS messages_expires_at ON messages(expires_at)",
            0,
        )
        cleanupDuplicatePendingMessages(driver)
    }

    private fun cleanupDuplicatePendingMessages(driver: SqlDriver) {
        driver.execute(
            null,
            """
            DELETE FROM messages
            WHERE pending_id IS NOT NULL
              AND pending_id != ''
              AND rowid NOT IN (
                SELECT MAX(rowid)
                FROM messages
                WHERE pending_id IS NOT NULL
                  AND pending_id != ''
                GROUP BY chat_id, sender_id, pending_id
              )
            """.trimIndent(),
            0,
        )
    }

    private fun ensureLocalAttachmentsTable(driver: SqlDriver) {
        if (!tableExists(driver, "local_attachments")) {
            driver.execute(
                null,
                """
                CREATE TABLE local_attachments (
                    cache_id TEXT NOT NULL PRIMARY KEY,
                    attachment_id TEXT,
                    message_id TEXT,
                    chat_id TEXT NOT NULL,
                    owner_account_id TEXT NOT NULL,
                    direction TEXT NOT NULL,
                    kind TEXT,
                    mime_type TEXT,
                    file_name TEXT,
                    width INTEGER,
                    height INTEGER,
                    duration_ms INTEGER,
                    waveform TEXT,
                    encrypted_path TEXT,
                    thumbnail_path TEXT,
                    thumbnail_key TEXT,
                    thumbnail_size INTEGER NOT NULL DEFAULT 0,
                    encrypted_size INTEGER NOT NULL DEFAULT 0,
                    plaintext_size INTEGER NOT NULL DEFAULT 0,
                    state TEXT NOT NULL,
                    last_accessed_at TEXT,
                    created_at TEXT NOT NULL,
                    expires_at TEXT,
                    error TEXT
                )
                """.trimIndent(),
                0,
            )
        }
        val columns = readTableColumns(driver, "local_attachments")
        addColumnIfMissing(driver, "local_attachments", columns, "kind", "TEXT")
        addColumnIfMissing(driver, "local_attachments", columns, "width", "INTEGER")
        addColumnIfMissing(driver, "local_attachments", columns, "height", "INTEGER")
        addColumnIfMissing(driver, "local_attachments", columns, "duration_ms", "INTEGER")
        addColumnIfMissing(driver, "local_attachments", columns, "waveform", "TEXT")
        addColumnIfMissing(driver, "local_attachments", columns, "thumbnail_key", "TEXT")
        if ("thumbnail_size" !in columns) {
            driver.execute(
                null,
                "ALTER TABLE local_attachments ADD COLUMN thumbnail_size INTEGER NOT NULL DEFAULT 0",
                0,
            )
        }
        driver.execute(null, "CREATE INDEX IF NOT EXISTS local_attachments_attachment_id ON local_attachments(attachment_id)", 0)
        driver.execute(null, "CREATE INDEX IF NOT EXISTS local_attachments_message_id ON local_attachments(message_id)", 0)
        driver.execute(null, "CREATE INDEX IF NOT EXISTS local_attachments_chat_id ON local_attachments(chat_id)", 0)
        driver.execute(null, "CREATE INDEX IF NOT EXISTS local_attachments_state ON local_attachments(state)", 0)
        driver.execute(null, "CREATE INDEX IF NOT EXISTS local_attachments_trim ON local_attachments(last_accessed_at, created_at)", 0)
    }

    private fun addColumnIfMissing(
        driver: SqlDriver,
        table: String,
        columns: Set<String>,
        name: String,
        sqlType: String,
    ) {
        if (name !in columns) {
            driver.execute(null, "ALTER TABLE $table ADD COLUMN $name $sqlType", 0)
        }
    }

    private fun tableExists(driver: SqlDriver, table: String): Boolean {
        var exists = false
        driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            mapper = { cursor ->
                exists = cursor.next().value
                QueryResult.Unit
            },
            parameters = 1,
            binders = {
                bindString(0, table)
            },
        )
        return exists
    }

    private fun readTableColumns(driver: SqlDriver, table: String): Set<String> {
        val columns = mutableSetOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($table)",
            mapper = { cursor ->
                while (cursor.next().value) {
                    cursor.getString(1)?.let(columns::add)
                }
                QueryResult.Unit
            },
            parameters = 0,
        )
        return columns
    }
}
