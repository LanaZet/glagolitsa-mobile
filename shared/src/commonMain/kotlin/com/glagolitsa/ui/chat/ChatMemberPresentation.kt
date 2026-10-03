// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.ChannelSubscriberPolicy
import com.glagolitsa.model.Chat
import com.glagolitsa.model.GroupMemberDto
import com.glagolitsa.model.User
import com.glagolitsa.model.displayLabel

enum class ChatMemberListKind(
    val title: String,
    val loadError: String,
    val ownerTargetLabel: String,
    val kickLabel: String,
    val asSubscribers: Boolean,
    val listAlwaysVisible: Boolean,
) {
    Subscribers(
        title = "Подписчики",
        loadError = "Не удалось загрузить подписчиков",
        ownerTargetLabel = "канала",
        kickLabel = "Исключить из канала",
        asSubscribers = true,
        listAlwaysVisible = false,
    ),
    Participants(
        title = "Участники",
        loadError = "Не удалось загрузить участников",
        ownerTargetLabel = "чата",
        kickLabel = "Исключить из чата",
        asSubscribers = false,
        listAlwaysVisible = true,
    ),
}

data class ChatMemberRowUiModel(
    val name: String,
    val username: String?,
    val avatarUrl: String?,
    val roleLabel: String,
    val muted: Boolean,
    val isSelf: Boolean,
)

fun chatMemberListKind(chat: Chat): ChatMemberListKind? = when {
    chat.isChannel -> ChatMemberListKind.Subscribers
    chat.isGroup -> ChatMemberListKind.Participants
    else -> null
}

fun chatMemberCountText(count: Int, kind: ChatMemberListKind): String =
    if (kind.asSubscribers) {
        "$count ${subscriberLabel(count)}"
    } else {
        "$count ${participantLabel(count)}"
    }

fun chatMemberRowUiModel(
    member: GroupMemberDto,
    currentUser: User?,
    profile: User?,
    cachedUsername: String?,
    cachedAvatarUrl: String?,
    kind: ChatMemberListKind,
    nowMs: Long,
): ChatMemberRowUiModel =
    ChatMemberRowUiModel(
        name = chatMemberDisplayName(
            memberUserId = member.user_id,
            currentUser = currentUser,
            profile = profile,
            cachedUsername = cachedUsername,
        ),
        username = chatMemberUsername(member.user_id, currentUser, profile),
        avatarUrl = chatMemberAvatarUrl(
            memberUserId = member.user_id,
            currentUser = currentUser,
            profile = profile,
            cachedAvatarUrl = cachedAvatarUrl,
        ),
        roleLabel = ChannelSubscriberPolicy.roleLabel(member.role, asSubscriber = kind.asSubscribers),
        muted = ChannelSubscriberPolicy.isMuted(member, nowMs),
        isSelf = isChatMemberSelf(member.user_id, currentUser?.id),
    )

fun isChatMemberSelf(memberUserId: String, currentUserId: String?): Boolean {
    val me = currentUserId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return memberUserId == me
}

fun chatMemberDisplayName(
    memberUserId: String,
    currentUser: User?,
    profile: User?,
    cachedUsername: String?,
): String {
    if (isChatMemberSelf(memberUserId, currentUser?.id) && currentUser != null) {
        return currentUser.displayLabel().ifBlank { "Вы" }
    }
    return profile?.displayLabel()?.takeIf { it.isNotBlank() }
        ?: cachedUsername?.takeIf { it.isNotBlank() }
        ?: "Пользователь"
}

fun chatMemberUsername(
    memberUserId: String,
    currentUser: User?,
    profile: User?,
): String? {
    val raw = if (isChatMemberSelf(memberUserId, currentUser?.id)) {
        currentUser?.username
    } else {
        profile?.username
    }
    return raw?.trim()?.takeIf { it.isNotEmpty() }
}

fun chatMemberAvatarUrl(
    memberUserId: String,
    currentUser: User?,
    profile: User?,
    cachedAvatarUrl: String?,
): String? {
    if (isChatMemberSelf(memberUserId, currentUser?.id)) {
        currentUser?.avatar_url?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return profile?.avatar_url?.trim()?.takeIf { it.isNotEmpty() }
        ?: cachedAvatarUrl?.trim()?.takeIf { it.isNotEmpty() }
}
