// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.metadata

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SealedGroupPayload(
    val sender_account_id: String,
    val sender_device_id: String,
    val chat_id: String,
    val body: String,
    val client_message_id: String? = null,
    val attachment: SealedAttachmentRef? = null,
    val reply_to_message_id: String? = null,
    val reply_preview_sender_id: String? = null,
    val reply_preview_body: String? = null,
    val thread_root_id: String? = null,
    val thread_parent_id: String? = null,
    val visibility: String? = null,
    val expires_at_sec: Long? = null,
)

private val groupJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun encodeSealedGroupPayload(payload: SealedGroupPayload): ByteArray =
    groupJson.encodeToString(SealedGroupPayload.serializer(), payload).encodeToByteArray()

fun decodeSealedGroupPayload(bytes: ByteArray): SealedGroupPayload? =
    runCatching {
        groupJson.decodeFromString(SealedGroupPayload.serializer(), bytes.decodeToString())
    }.getOrNull()
