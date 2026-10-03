// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlinx.serialization.Serializable

@Serializable
data class SignedPreKeyMaterial(
    val id: Int,
    val public_key: String,
    val signature: String,
    val created_at: Long,
)

@Serializable
data class PqPreKeyMaterial(
    val id: Int,
    val public_material: String,
    val signature: String,
    val created_at: Long,
)

@Serializable
data class OneTimePreKeyMaterial(
    val id: Int,
    val public_key: String,
)

@Serializable
data class RegisterDeviceResponse(
    val device_id: String,
    val prekeys_stored: Int = 0,
    val one_time_prekey_ids: List<Int> = emptyList(),
)

@Serializable
data class DeviceAttestation(
    val confirming_device_id: String = "",
    val signature: String = "",
    val provider: String? = null,
    val token: String? = null,
)

@Serializable
data class ConfirmDeviceRequest(
    val confirming_device_id: String,
)

@Serializable
data class RegisterDeviceRequest(
    val device_id: String,
    val registration_id: Int,
    val identity_public_key: String,
    val signed_prekey: SignedPreKeyMaterial,
    val pq_prekey: PqPreKeyMaterial,
    val one_time_prekeys: List<OneTimePreKeyMaterial>,
    val attestation: DeviceAttestation? = null,
)

@Serializable
data class RotateSignedPreKeyRequest(
    val signed_prekey: SignedPreKeyMaterial,
    val pq_prekey: PqPreKeyMaterial,
)

@Serializable
data class DeviceKeyBundle(
    val device_id: String,
    val account_id: String,
    val registration_id: Int,
    val identity_public_key: String,
    val signed_prekey: SignedPreKeyMaterial,
    val pq_prekey: PqPreKeyMaterial,
    val one_time_prekey: OneTimePreKeyMaterial? = null,
)

@Serializable
data class UserDevice(
    val device_id: String,
    val mailbox_token: String,
    val registration_id: Int,
    val identity_public_key: String,
    val device_status: String? = null,
    val delivery_token: String? = null,
)

@Serializable
data class PrekeyCountResponse(
    val device_id: String,
    val remaining_prekeys: Int,
)

@Serializable
data class KeyChangeEvent(
    val id: String,
    val device_id: String,
    val event_type: String,
    val identity_key_hash: String,
    val signed_prekey_id: Int = 0,
    val prev_event_hash: String? = null,
    val event_hash: String,
    val created_at: String,
)

@Serializable
data class SafetyNumberMaterial(
    val device_id: String,
    val account_id: String,
    val registration_id: Int,
    val identity_public_key: String,
)

@Serializable
data class RelayEnvelopeRequest(
    val mailbox_token: String,
    val delivery_token: String? = null,
    val envelope_type: Int,
    val ciphertext: String,
)

@Serializable
data class RelayMessageRequest(
    val pairwise_id: String? = null,
    val client_message_id: String? = null,
    val envelopes: List<RelayEnvelopeRequest>,
    /** Relay queue TTL override in seconds (omit for server default). */
    val expires_at_sec: Long? = null,
)

@Serializable
data class RelayMessageResponse(
    val enqueued: Int,
    val envelope_ids: List<String> = emptyList(),
)

@Serializable
data class QueuedEnvelope(
    val envelope_id: String,
    val mailbox_token: String,
    val envelope_type: Int,
    val ciphertext: String,
    val size_bucket: Int,
    val created_at: String? = null,
    val expires_at: String? = null,
)

@Serializable
data class MessageQueueResponse(
    val envelopes: List<QueuedEnvelope>,
)

@Serializable
data class AckMessageQueueRequest(
    val envelope_ids: List<String>,
)

@Serializable
data class AckMessageQueueResponse(
    val deleted: Int,
)

@Serializable
data class ReplenishPrekeysRequest(
    val one_time_prekeys: List<OneTimePreKeyMaterial>,
)

@Serializable
data class SaveSyncSnapshotRequest(
    val event_id: Long,
    val snapshot_data: String,
)

@Serializable
data class SyncSnapshotResponse(
    val event_id: Long,
    val snapshot_data: String? = null,
    val created_at: String,
)

@Serializable
data class SyncResponse(
    val server_time: String,
    val chats: List<Chat> = emptyList(),
    val events: List<ChatEventDto> = emptyList(),
    val has_more: Boolean = false,
)

@Serializable
data class ChatEventDto(
    val id: String,
    val chat_id: String = "",
    val event_type: String,
    val actor_id: String = "",
    val entity_id: String = "",
    val created_at: String,
)

@Serializable
data class EnvelopeNewData(
    val envelope_id: String,
    val mailbox_token: String,
)

@Serializable
data class CreateAttachmentResponse(
    val attachment_id: String,
    val expires_at: String,
)

@Serializable
data class AttachmentInfo(
    val attachment_id: String,
    val size_bytes: Long,
    val size_bucket: Int,
    val expires_at: String? = null,
    val created_at: String? = null,
)

@Serializable
data class RotateMailboxResponse(
    val device_id: String,
    val mailbox_token: String,
    val previous_mailbox_token: String? = null,
    val previous_expires_at: String? = null,
)
