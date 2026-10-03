// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.metadata

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SealedAttachmentRef(
    val attachment_id: String,
    val file_key: String,
    val file_name: String? = null,
    val mime_type: String? = null,
    val plaintext_size: Long,
    val kind: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val duration_ms: Long? = null,
    val thumbnail: String? = null,
    val waveform: List<Int> = emptyList(),
)

const val PAYLOAD_KIND_DM = "dm"
const val PAYLOAD_KIND_DM_DELETE = "dm_delete"
const val PAYLOAD_KIND_DM_READ_RECEIPT = "dm_read_receipt"
const val PAYLOAD_KIND_GROUP_SKDM = "group_skdm"

@Serializable
data class SealedDmPayload(
    val pairwise_id: String,
    val sender_account_id: String,
    val sender_device_id: String,
    val chat_id: String,
    val body: String = "",
    val client_message_id: String? = null,
    val created_at: String? = null,
    val attachment: SealedAttachmentRef? = null,
    val kind: String = PAYLOAD_KIND_DM,
    val read_message_ids: List<String> = emptyList(),
    val deleted_message_ids: List<String> = emptyList(),
    /** Base64 SenderKeyDistributionMessage for [PAYLOAD_KIND_GROUP_SKDM]. */
    val group_skdm: String? = null,
    val group_chat_id: String? = null,
    /** Message TTL in seconds; local purge + relay queue expiry when set. */
    val expires_at_sec: Long? = null,
    /** Inline reply / thread metadata (ciphertext-only; server is opaque). */
    val reply_to_message_id: String? = null,
    val reply_preview_sender_id: String? = null,
    val reply_preview_body: String? = null,
    val thread_root_id: String? = null,
    val thread_parent_id: String? = null,
    val visibility: String? = null,
)

private val sealedJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

const val PAIRWISE_ID_BYTE_LENGTH = 16

fun encodeSealedDmPayload(payload: SealedDmPayload): ByteArray =
    sealedJson.encodeToString(SealedDmPayload.serializer(), payload).encodeToByteArray()

fun decodeSealedDmPayload(bytes: ByteArray): SealedDmPayload? =
    runCatching {
        sealedJson.decodeFromString(SealedDmPayload.serializer(), bytes.decodeToString())
    }.getOrNull()

fun prependPairwiseId(pairwiseId: String, ciphertext: ByteArray): ByteArray {
    val idBytes = uuidToBytes(pairwiseId)
    return idBytes + ciphertext
}

fun extractPairwiseId(ciphertext: ByteArray): Pair<String, ByteArray>? {
    if (ciphertext.size <= PAIRWISE_ID_BYTE_LENGTH) return null
    val pairwiseId = bytesToUuid(ciphertext.copyOfRange(0, PAIRWISE_ID_BYTE_LENGTH))
    val payload = ciphertext.copyOfRange(PAIRWISE_ID_BYTE_LENGTH, ciphertext.size)
    return pairwiseId to payload
}

fun uuidToBytes(uuid: String): ByteArray {
    val normalized = uuid.replace("-", "")
    require(normalized.length == 32) { "invalid uuid" }
    return ByteArray(PAIRWISE_ID_BYTE_LENGTH) { index ->
        normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

fun bytesToUuid(bytes: ByteArray): String {
    require(bytes.size == PAIRWISE_ID_BYTE_LENGTH) { "pairwise id must be 16 bytes" }
    val hex = buildString(bytes.size * 2) {
        for (byte in bytes) {
            append(((byte.toInt() and 0xFF) ushr 4).toString(16))
            append((byte.toInt() and 0x0F).toString(16))
        }
    }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
