// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlinx.serialization.Serializable

@Serializable
data class ReactionSummary(
    val emoji: String,
    val count: Int = 0,
    val me: Boolean = false,
)

@Serializable
data class Message(
    val id: String,
    val chat_id: String,
    val sender_id: String,
    val body: String = "",
    val pending_id: String? = null,
    val status: String? = null,
    val created_at: String? = null,
    val reply_to_message_id: String? = null,
    val reply_preview_sender_id: String? = null,
    val reply_preview_body: String? = null,
    val thread_root_id: String? = null,
    val thread_parent_id: String? = null,
    val visibility: String? = null,
    val thread_reply_count: Int = 0,
    val last_thread_reply_at: String? = null,
    val last_thread_reply_sender_id: String? = null,
    val envelope_type: Int? = null,
    val ciphertext: String? = null,
    val sender_device_id: String? = null,
    val expires_at: String? = null,
    /** Open channel post extras (media[], tags). */
    val metadata: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    val reactions: List<ReactionSummary> = emptyList(),
)

@Serializable
data class SendMessageRequest(
    val body: String = "",
    val pending_id: String? = null,
    val reply_to_message_id: String? = null,
    val thread_root_id: String? = null,
    val thread_parent_id: String? = null,
    val visibility: String? = null,
    val envelope_type: Int? = null,
    val ciphertext: String? = null,
    val sender_device_id: String? = null,
    val metadata: Map<String, kotlinx.serialization.json.JsonElement>? = null,
)

@Serializable
data class SetReactionRequest(
    val emoji: String,
)

@Serializable
data class ReactionUpdateResponse(
    val message_id: String? = null,
    val summary: List<ReactionSummary> = emptyList(),
)

@Serializable
data class MessagesPageResponse(
    val messages: List<Message>,
    val has_more: Boolean = false,
)

@Serializable
data class MessageRelationDraft(
    val replyToMessageId: String? = null,
    val replyPreviewSenderId: String? = null,
    val replyPreviewBody: String? = null,
    val threadRootId: String? = null,
    val threadParentId: String? = null,
    val visibility: String? = null,
)

const val MESSAGE_VISIBILITY_MAIN = "main"
const val MESSAGE_VISIBILITY_THREAD_ONLY = "thread_only"

fun Message.isThreadOnly(): Boolean = visibility == MESSAGE_VISIBILITY_THREAD_ONLY
