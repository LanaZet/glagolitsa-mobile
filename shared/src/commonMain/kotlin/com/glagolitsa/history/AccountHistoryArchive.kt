// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.history

import com.glagolitsa.model.Chat
import com.glagolitsa.model.Message
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Account-scoped chat history for **Signal-style Secure Backups**.
 *
 * Live transport stays libsignal E2E. This archive is a client-side encrypted
 * copy of already-decrypted local history. The unlock secret is a high-entropy
 * **recovery key** generated on-device — never the login password, never held
 * by the server (zero-knowledge).
 */
@Serializable
data class AccountHistoryArchive(
    val version: Int = 1,
    val user_id: String,
    val exported_at: String,
    val chats: List<Chat> = emptyList(),
    val messages: List<Message> = emptyList(),
) {
    val messageCount: Int get() = messages.size
}

internal val accountHistoryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun AccountHistoryArchive.encodeToBytes(): ByteArray =
    accountHistoryJson.encodeToString(AccountHistoryArchive.serializer(), this).encodeToByteArray()

fun decodeAccountHistoryArchive(bytes: ByteArray): AccountHistoryArchive =
    accountHistoryJson.decodeFromString(
        AccountHistoryArchive.serializer(),
        bytes.decodeToString(),
    )
