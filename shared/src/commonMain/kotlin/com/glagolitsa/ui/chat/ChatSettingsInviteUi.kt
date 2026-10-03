// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.Chat
import com.glagolitsa.model.GroupRoles

/**
 * Pure helpers for chat-settings invite / membership UI (no network, no persistence).
 */
object ChatSettingsInviteUi {
    /** Public deep-link style path shown in settings. Empty when no usable link exists. */
    fun inviteLinkLabel(chat: Chat): String {
        if (chat.isDirectMessage) return ""
        val slug = chat.slug?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            chat.isChannel && slug != null -> "https://glagolitsa.app/c/$slug"
            else -> ""
        }
    }

    fun showInviteSection(chat: Chat, currentUserId: String?, canManageInvites: Boolean? = null): Boolean {
        if (!isMultiParty(chat)) return false
        if (canManageInvites != null) return canManageInvites
        return isCreator(chat, currentUserId)
    }

    fun showPermissionsSection(canChangeInfo: Boolean): Boolean = canChangeInfo

    fun isMultiParty(chat: Chat): Boolean = chat.isChannel || chat.isGroup

    /**
     * Only the channel/group **creator** owns admin settings.
     * When [creator_id] is unknown, treat as non-creator (safer for third parties).
     */
    fun isCreator(chat: Chat, currentUserId: String?): Boolean {
        val creator = chat.creator_id?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val me = currentUserId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return creator == me
    }

    /**
     * Membership: prefer explicit [Chat.member_ids].
     * If the list is empty but the chat is open in the app, treat as member
     * (list chats often omit ids until sync fills them).
     */
    fun isMember(chat: Chat, currentUserId: String?, openedFromMembership: Boolean = true): Boolean {
        val me = currentUserId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (chat.member_ids.any { it == me }) return true
        if (chat.member_ids.isEmpty() && openedFromMembership && isMultiParty(chat)) return true
        return false
    }

    /**
     * Third-party (not creator): join / follow / leave — never admin tools.
     */
    fun showAudienceActions(
        chat: Chat,
        currentUserId: String?,
        membershipRole: String? = null,
    ): Boolean =
        isMultiParty(chat) &&
            !isCreator(chat, currentUserId) &&
            !membershipRole.equals(GroupRoles.OWNER, ignoreCase = true)

    fun showJoinAction(
        chat: Chat,
        currentUserId: String?,
        openedFromMembership: Boolean = true,
        membershipRole: String? = null,
    ): Boolean =
        showAudienceActions(chat, currentUserId, membershipRole) &&
            !isMember(chat, currentUserId, openedFromMembership) &&
            chat.isPublicChannel &&
            !chat.slug.isNullOrBlank()

    fun showFollowActions(
        chat: Chat,
        currentUserId: String?,
        openedFromMembership: Boolean = true,
        membershipRole: String? = null,
    ): Boolean =
        showAudienceActions(chat, currentUserId, membershipRole) &&
            isMember(chat, currentUserId, openedFromMembership)

    fun showLeaveAction(
        chat: Chat,
        currentUserId: String?,
        openedFromMembership: Boolean = true,
        membershipRole: String? = null,
    ): Boolean =
        !showDeleteAction(chat, currentUserId, membershipRole) &&
            showAudienceActions(chat, currentUserId, membershipRole) &&
            isMember(chat, currentUserId, openedFromMembership)

    /** Owner-only: Mattermost-style delete channel/group for everyone. */
    fun showDeleteAction(
        chat: Chat,
        currentUserId: String?,
        membershipRole: String? = null,
    ): Boolean {
        if (!isMultiParty(chat)) return false
        if (isCreator(chat, currentUserId)) return true
        return membershipRole.equals(GroupRoles.OWNER, ignoreCase = true)
    }

    /** Direct messages have no membership — hide locally from the list. */
    fun showHideLocalAction(chat: Chat): Boolean = chat.isDirectMessage

    fun showRemoveAction(
        chat: Chat,
        currentUserId: String?,
        membershipRole: String? = null,
        openedFromMembership: Boolean = true,
    ): Boolean =
        showHideLocalAction(chat) ||
            showDeleteAction(chat, currentUserId, membershipRole) ||
            showLeaveAction(chat, currentUserId, openedFromMembership, membershipRole)

    fun chatKindLabel(chat: Chat): String = when {
        chat.isDirectMessage -> "Личный чат"
        chat.isChannel && chat.isPublicChannel -> "Публичный канал"
        chat.isChannel -> "Личный канал"
        chat.isGroup -> "Группа"
        else -> "Чат"
    }

    fun membershipRoleLabel(chat: Chat, role: String?): String = when (role?.lowercase()) {
        GroupRoles.OWNER -> "владелец"
        GroupRoles.ADMIN -> "админ"
        else -> if (chat.isChannel) "подписчик" else "участник"
    }

    fun membershipRoleDescription(chat: Chat, role: String?): String {
        val isChannel = chat.isChannel
        return when (role?.lowercase()) {
            GroupRoles.OWNER -> if (isChannel) {
                "Вы владелец канала. Можно менять иконку, приглашать по ссылке и назначать админов."
            } else {
                "Вы владелец группы. Можно менять иконку, приглашать по ссылке и назначать админов."
            }
            GroupRoles.ADMIN -> if (isChannel) {
                "Вы администратор канала. Можно управлять подписчиками и публиковать посты."
            } else {
                "Вы администратор. Можно управлять участниками и приглашать по ссылке."
            }
            else -> if (isChannel) {
                "Вы подписчик. Можно отслеживать обновления или выйти."
            } else {
                "Вы участник. Можно отслеживать обновления или выйти."
            }
        }
    }
}
