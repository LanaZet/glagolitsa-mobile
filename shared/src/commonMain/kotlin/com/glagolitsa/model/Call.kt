// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlinx.serialization.Serializable

object CallType {
    const val AUDIO = "audio"
    const val VIDEO = "video"
}

object CallStatus {
    const val RINGING = "ringing"
    const val CONNECTING = "connecting"
    const val ACTIVE = "active"
    const val ENDED = "ended"
    const val REJECTED = "rejected"
    const val MISSED = "missed"
}

object CallScope {
    const val DM = "dm"
    const val GROUP = "group"
    const val AD_HOC = "ad_hoc"
    const val LINK = "link"
}

@Serializable
data class CreateCallRequest(
    /** Legacy 1:1 field; server maps to DM call + participants. */
    val callee_id: String? = null,
    val chat_id: String? = null,
    val initial_invitee_ids: List<String>? = null,
    val call_type: String = CallType.AUDIO,
    val device_id: String? = null,
    val call_scope: String? = null,
)

@Serializable
data class CallParticipant(
    val user_id: String,
    val device_id: String? = null,
    val role: String? = null,
    val invite_state: String? = null,
    val media_state: String? = null,
    val joined_at: String? = null,
    val left_at: String? = null,
)

@Serializable
data class CallSession(
    val id: String,
    val caller_id: String,
    /** Legacy denormalized peer for 1:1; empty/absent for multi-party. */
    val callee_id: String = "",
    val chat_id: String? = null,
    val started_by_user_id: String? = null,
    val call_scope: String? = null,
    val call_type: String = CallType.AUDIO,
    val status: String = CallStatus.RINGING,
    val livekit_room_id: String,
    val caller_device_id: String? = null,
    val selected_region: String? = null,
    val route_class: String? = null,
    val policy_version: Int? = null,
    val low_bandwidth_mode: Boolean = false,
    val created_at: String,
    val accepted_at: String? = null,
    val connected_at: String? = null,
    val ended_at: String? = null,
    val duration_sec: Int = 0,
    val participants: List<CallParticipant> = emptyList(),
)

@Serializable
data class CallActionResponse(
    val call: CallSession,
)

@Serializable
data class ActiveCallResponse(
    val call: CallSession? = null,
)

@Serializable
data class CallTokenResponse(
    val token: String,
    val livekit_url: String,
    val room_name: String,
    val participant_id: String,
    val media_config: CallMediaConfig,
    val selected_region: String? = null,
    val route_class: String? = null,
)

@Serializable
data class MarkCallConnectedRequest(
    val media_path_confirmed: Boolean,
)

@Serializable
data class CallMediaConfig(
    val e2ee: Boolean = true,
    val adaptive_stream: Boolean = true,
    val dynacast: Boolean = true,
    val simulcast: Boolean = false,
    val low_bandwidth_auto: Boolean = true,
    val low_bandwidth_mode: Boolean = false,
    val audio_first: Boolean = true,
    val policy_version: Int = 1,
    val audio: CallAudioConfig = CallAudioConfig(),
    val video: CallVideoConfig = CallVideoConfig(),
    val network_hint: String? = null,
)

@Serializable
data class CallAudioConfig(
    val codec: String = "opus",
    val mono: Boolean = true,
    val bitrate_kbps: Int = 24,
    val dtx: Boolean = true,
    val noise_suppression: Boolean = true,
    val echo_cancellation: Boolean = true,
    val auto_gain_control: Boolean = true,
)

@Serializable
data class CallVideoConfig(
    val enabled: Boolean = false,
    val max_width: Int? = null,
    val max_height: Int? = null,
    val max_fps: Int? = null,
    val simulcast: Boolean = false,
)

@Serializable
data class ICEServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class ICEServersResponse(
    val servers: List<ICEServer> = emptyList(),
    val ttl_seconds: Int = 0,
)

@Serializable
data class SubmitCallKeysRequest(
    val offers: List<CallKeyOfferInput>,
)

@Serializable
data class CallKeyOfferInput(
    val target_user_id: String,
    val target_device_id: String,
    val envelope_type: Int,
    val encrypted_key: String,
)

@Serializable
data class CallKeyOffer(
    val source_user_id: String,
    val source_device_id: String,
    val target_user_id: String,
    val target_device_id: String,
    val envelope_type: Int,
    val encrypted_key: String,
    val created_at: String,
)

enum class CallRole {
    Outgoing,
    Incoming,
}

data class CallUiState(
    val session: CallSession,
    val partnerName: String,
    val role: CallRole,
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
    val connectingMedia: Boolean = false,
    val lowBandwidthNotice: String? = null,
)

fun CallSession.partnerIdFor(userId: String): String {
    if (callee_id.isNotBlank() && caller_id == userId) return callee_id
    if (callee_id.isNotBlank() && callee_id == userId) return caller_id
    // Group-ready fallback: first other participant / started_by peer.
    val fromParticipants = participants.firstOrNull { it.user_id != userId }?.user_id
    if (!fromParticipants.isNullOrBlank()) return fromParticipants
    if (!started_by_user_id.isNullOrBlank() && started_by_user_id != userId) {
        return started_by_user_id
    }
    return callee_id.ifBlank { caller_id }
}

/**
 * Photo for the in-call disc: group icon when this is a chat call, otherwise the
 * other person's avatar. Empty string means "use the monogram fallback".
 */
fun CallSession.displayAvatarUrl(
    selfId: String?,
    userAvatarUrl: (userId: String) -> String?,
    chatAvatarUrl: String? = null,
): String? {
    if (isGroupChatCall()) {
        chatAvatarUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    val peerId = when {
        !selfId.isNullOrBlank() -> partnerIdFor(selfId)
        else -> caller_id
    }.trim()
    if (peerId.isEmpty()) return null
    return userAvatarUrl(peerId)?.trim()?.takeIf { it.isNotEmpty() }
}

fun CallSession.isTerminal(): Boolean =
    status == CallStatus.ENDED || status == CallStatus.REJECTED || status == CallStatus.MISSED

object CallConnectionPolicy {
    fun canStartMedia(session: CallSession): Boolean =
        session.status == CallStatus.RINGING || session.status == CallStatus.CONNECTING

    fun canMarkConnected(activeCallId: String?, session: CallSession): Boolean {
        if (activeCallId == null || activeCallId != session.id) return false
        if (session.isTerminal()) return false
        return session.status == CallStatus.RINGING ||
            session.status == CallStatus.CONNECTING ||
            session.status == CallStatus.ACTIVE
    }

    fun isAccepted(session: CallSession): Boolean =
        session.status == CallStatus.CONNECTING || session.status == CallStatus.ACTIVE
}

fun CallSession.historyStatusLabel(selfId: String?): String = when (status) {
    CallStatus.RINGING -> if (callee_id == selfId) "Входящий" else "Исходящий"
    CallStatus.CONNECTING -> "Подключение"
    CallStatus.ACTIVE -> "Разговор"
    CallStatus.ENDED -> if (duration_sec > 0) "Завершён · ${duration_sec} с" else "Завершён"
    CallStatus.REJECTED -> "Отклонён"
    CallStatus.MISSED -> "Пропущен"
    else -> status
}

/**
 * Compact multi-part label for call history rows:
 * direction/status · type · time · optional duration.
 *
 * Unlike [historyStatusLabel], duration is a separate segment so ended calls
 * do not embed seconds twice.
 */
fun CallSession.historyDetailsLabel(selfId: String?): String {
    val direction = when (status) {
        CallStatus.RINGING -> if (callee_id == selfId) "Входящий" else "Исходящий"
        CallStatus.CONNECTING -> "Подключение"
        CallStatus.ACTIVE -> "Разговор"
        CallStatus.ENDED -> "Завершён"
        CallStatus.REJECTED -> "Отклонён"
        CallStatus.MISSED -> "Пропущен"
        else -> status
    }
    val type = when (call_type) {
        CallType.VIDEO -> "Видео"
        else -> "Аудио"
    }
    val time = formatMessageTime(created_at)
    val duration = if (duration_sec > 0) {
        val minutes = duration_sec / 60
        val seconds = duration_sec % 60
        if (minutes > 0) "${minutes} мин ${seconds} с" else "${seconds} с"
    } else {
        null
    }
    return listOfNotNull(direction, type, time.takeIf { it.isNotBlank() }, duration)
        .joinToString(" · ")
}

fun CallUiState.activeStatusLabel(): String = when (session.status) {
    CallStatus.RINGING -> when (role) {
        CallRole.Outgoing -> "Звоним…"
        CallRole.Incoming -> "Входящий звонок"
    }
    CallStatus.CONNECTING -> "Подключение…"
    CallStatus.ACTIVE -> "На линии"
    CallStatus.ENDED -> "Звонок завершён"
    CallStatus.REJECTED -> when (role) {
        CallRole.Outgoing -> "Абонент занят"
        CallRole.Incoming -> "Звонок отклонён"
    }
    CallStatus.MISSED -> "Пропущенный звонок"
    else -> session.status
}
