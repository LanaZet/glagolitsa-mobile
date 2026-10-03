// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlinx.serialization.Serializable

object PresenceStatus {
    const val ONLINE = "online"
    const val OFFLINE = "offline"
    const val IN_CALL = "in_call"
    const val HIDDEN = "hidden"
    const val TYPING = "typing"
    const val RECORDING_VOICE = "recording_voice"
    const val RECORDING_VIDEO = "recording_video"
}

object PresenceVisibility {
    const val ALL = "all"
    const val CONTACTS = "contacts"
    const val NOBODY = "nobody"
}

object LastSeenBucket {
    const val JUST_NOW = "just_now"
    const val RECENTLY = "recently"
    const val TODAY = "today"
    const val THIS_WEEK = "this_week"
    const val LONG_AGO = "long_ago"
}

@Serializable
data class HeartbeatRequest(
    val device_id: String? = null,
)

@Serializable
data class TypingChatRequest(
    val chat_id: String,
)

@Serializable
data class RecordingPresenceRequest(
    val chat_id: String,
    val kind: String? = null,
)

@Serializable
data class PresencePrivacySettings(
    val online_visibility: String = PresenceVisibility.CONTACTS,
    val last_seen_visibility: String = PresenceVisibility.CONTACTS,
)

@Serializable
data class UpdatePresencePrivacyRequest(
    val online_visibility: String? = null,
    val last_seen_visibility: String? = null,
)

@Serializable
data class UserPresenceView(
    val user_id: String,
    val status: String = PresenceStatus.OFFLINE,
    val last_seen_bucket: String? = null,
    val in_call: Boolean = false,
    val call_id: String? = null,
    val active_devices: List<String> = emptyList(),
)

@Serializable
data class UsersPresenceResponse(
    val users: List<UserPresenceView> = emptyList(),
)

@Serializable
data class ChatPresenceView(
    val chat_id: String,
    val typing_user_ids: List<String> = emptyList(),
    val recording: Map<String, String> = emptyMap(),
)

fun UserPresenceView.isVisibleInCall(): Boolean =
    status != PresenceStatus.HIDDEN && (in_call || status == PresenceStatus.IN_CALL)

fun UserPresenceView.isOnlineLike(): Boolean =
    status != PresenceStatus.HIDDEN && (status == PresenceStatus.ONLINE || isVisibleInCall())

/**
 * Realtime **network** presence label for chat UI.
 * Must never incorporate profile about text.
 */
fun UserPresenceView.statusLabel(): String = when {
    status == PresenceStatus.HIDDEN -> lastSeenBucketLabel(last_seen_bucket) ?: "не в сети"
    isVisibleInCall() -> "в звонке"
    status == PresenceStatus.ONLINE -> "в сети"
    status == PresenceStatus.OFFLINE -> lastSeenBucketLabel(last_seen_bucket) ?: "не в сети"
    // Typing/recording are ephemeral overlays — still network-channel, not profile about.
    status == PresenceStatus.TYPING -> "печатает…"
    status == PresenceStatus.RECORDING_VOICE -> "записывает голосовое…"
    status == PresenceStatus.RECORDING_VIDEO -> "записывает видео…"
    else -> lastSeenBucketLabel(last_seen_bucket) ?: status
}

/** Privacy: contacts must not learn online/last-seen when both are nobody. */
fun PresencePrivacySettings.isNetworkVisible(): Boolean =
    StatusChannels.isNetworkStatusVisible(this)

fun lastSeenBucketLabel(bucket: String?): String? = when (bucket) {
    LastSeenBucket.JUST_NOW -> "только что"
    LastSeenBucket.RECENTLY -> "был(а) недавно"
    LastSeenBucket.TODAY -> "был(а) сегодня"
    LastSeenBucket.THIS_WEEK -> "был(а) на этой неделе"
    LastSeenBucket.LONG_AGO -> "был(а) давно"
    else -> null
}
