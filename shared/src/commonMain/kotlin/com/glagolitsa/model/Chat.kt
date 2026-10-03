// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlinx.serialization.Serializable

object ChatType {
    const val GROUP = "group"
    const val DIRECT = "dm"
    const val CHANNEL = "channel"
}

object ChatVisibility {
    const val PRIVATE = "private"
    const val PUBLIC = "public"
}

object ChatEncryption {
    const val E2E = "e2e"
    const val NONE = "none"
}

@Serializable
data class Chat(
    val id: String,
    val title: String,
    val type: String = ChatType.GROUP,
    val member_ids: List<String> = emptyList(),
    val last_message: String? = null,
    val last_message_at: String? = null,
    val created_at: String? = null,
    val description: String? = null,
    val visibility: String? = null,
    val slug: String? = null,
    val encryption: String? = null,
    /** Channel/group creator — role assignment is limited to this user on the client. */
    val creator_id: String? = null,
    /** data:image URI for group/channel icon. DMs use the partner profile avatar instead. */
    val avatar_url: String? = null,
) {
    val isDirectMessage: Boolean
        get() = type.equals(ChatType.DIRECT, ignoreCase = true)

    val isChannel: Boolean
        get() = type.equals(ChatType.CHANNEL, ignoreCase = true)

    val isGroup: Boolean
        get() = type.equals(ChatType.GROUP, ignoreCase = true)

    val isPublicChannel: Boolean
        get() = isChannel &&
            visibility.equals(ChatVisibility.PUBLIC, ignoreCase = true)

    val conversationIconUrl: String?
        get() = avatar_url?.trim()?.takeIf { it.isNotEmpty() }

    /** DM and private groups use the sealed e2e attachment path. Channels use open media. */
    val supportsE2eAttachments: Boolean
        get() = isDirectMessage || isGroup
}

/** Две буквы для аватара в списке чатов. */
fun Chat.listAvatarLabel(): String {
    val words = title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        title.isNotEmpty() -> title.take(2)
        else -> "?"
    }.uppercase()
}

@Serializable
data class CreateChatRequest(
    val title: String,
    val member_ids: List<String> = emptyList(),
    val avatar_url: String? = null,
)

@Serializable
data class UpdateChatRequest(
    val avatar_url: String? = null,
)

@Serializable
data class CreateDMRequest(
    val user_id: String,
)

@Serializable
data class CreateChannelRequest(
    val title: String,
    val description: String? = null,
    /** Private-only in product for now; public/slug may return later. */
    val visibility: String = ChatVisibility.PRIVATE,
    val slug: String? = null,
    val avatar_url: String? = null,
)

@Serializable
data class ChannelResponse(
    val id: String,
    val title: String,
    val type: String = ChatType.CHANNEL,
    val description: String? = null,
    val visibility: String? = null,
    val slug: String? = null,
    val encryption: String? = null,
    val avatar_url: String? = null,
    val member_ids: List<String> = emptyList(),
    val created_at: String? = null,
    val membership_version: Int? = null,
) {
    fun toChat(creatorId: String? = null): Chat = Chat(
        id = id,
        title = title,
        type = type.ifBlank { ChatType.CHANNEL },
        member_ids = member_ids,
        created_at = created_at,
        description = description,
        visibility = visibility,
        slug = slug,
        encryption = encryption,
        creator_id = creatorId,
        avatar_url = avatar_url,
    )
}

@Serializable
data class SlugAvailabilityResponse(
    val slug: String? = null,
    val available: Boolean = false,
)
