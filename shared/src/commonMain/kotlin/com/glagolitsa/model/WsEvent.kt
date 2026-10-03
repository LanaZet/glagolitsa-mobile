// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

object WsEventType {
    const val MESSAGE_NEW = "message.new"
    const val MESSAGE_ENVELOPE = "message.envelope"
    const val CHAT_UPDATED = "chat.updated"
    const val PRESENCE_ONLINE = "presence.online"
    const val PRESENCE_OFFLINE = "presence.offline"
    const val TYPING_STARTED = "typing.started"
    const val TYPING_STOPPED = "typing.stopped"
    const val TYPING_LEGACY = "typing"
    const val RECORDING_STARTED = "recording.started"
    const val RECORDING_STOPPED = "recording.stopped"
    // Presence in_call fanout (legacy names).
    const val CALL_STARTED = "call.started"
    const val CALL_ENDED = "call.ended"
    // Control-plane call events (calling package). Polling remains fallback.
    const val CALL_CREATED = "call.created"
    const val CALL_RINGING = "call.ringing"
    const val CALL_ACCEPTED = "call.accepted"
    const val CALL_CONNECTED = "call.connected"
    const val CALL_REJECTED = "call.rejected"
    // call.ended is shared with presence; decode by payload shape.
    const val CALL_PARTICIPANT_JOINED = "call.participant_joined"
    const val CALL_PARTICIPANT_LEFT = "call.participant_left"
    const val CALL_LOW_BANDWIDTH = "call.low_bandwidth"
    const val CALL_ROUTE_DEGRADED = "call.route_degraded"
}

@Serializable
data class WsEvent(
    val event: String,
    val data: JsonElement? = null,
)

@Serializable
data class MessageNewData(
    val message: Message,
)

@Serializable
data class ChatUpdatedData(
    val chat: Chat,
)

@Serializable
data class PresenceUserData(
    val user_id: String,
)

/** call.started / call.ended fanout payload from presence service. */
@Serializable
data class CallPresenceData(
    val user_id: String,
    val call_id: String? = null,
)

/**
 * Privacy-safe call control payload (ids only — no tokens, names, or keys).
 * Matches server calling.CallEventData.
 */
@Serializable
data class CallControlEventData(
    val call_id: String,
    val status: String? = null,
    val call_type: String? = null,
    val call_scope: String? = null,
    val user_id: String? = null,
    val chat_id: String? = null,
    val route_class: String? = null,
)