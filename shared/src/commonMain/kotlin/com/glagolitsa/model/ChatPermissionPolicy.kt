// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Telegram-like rights for groups and channels.
 *
 * Owner has every flag. Admins use [AdminRightsDto]. Members use default
 * [GroupSettingsDto] perm_* values (`admin` | `all` | `none`).
 */
object ChatPermissionPolicy {
    fun memberOf(group: GroupResponse, userId: String?): GroupMemberDto? {
        val me = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return group.members.firstOrNull { it.user_id == me }
    }

    fun isOwner(group: GroupResponse, userId: String?): Boolean =
        memberOf(group, userId)?.role.equals(GroupRoles.OWNER, ignoreCase = true)

    fun isAdmin(group: GroupResponse, userId: String?): Boolean {
        val role = memberOf(group, userId)?.role?.lowercase() ?: return false
        return role == GroupRoles.OWNER || role == GroupRoles.ADMIN
    }

    fun canInvite(group: GroupResponse, userId: String?): Boolean {
        val member = memberOf(group, userId) ?: return false
        return when (member.role.lowercase()) {
            GroupRoles.OWNER -> true
            GroupRoles.ADMIN -> rightsOf(member).invite_users
            else -> group.settings.perm_invite.equals(GroupPerms.ALL, ignoreCase = true)
        }
    }

    fun canChangeInfo(group: GroupResponse, userId: String?): Boolean {
        val member = memberOf(group, userId) ?: return false
        return when (member.role.lowercase()) {
            GroupRoles.OWNER -> true
            GroupRoles.ADMIN -> rightsOf(member).change_info
            else -> group.settings.perm_change_info.equals(GroupPerms.ALL, ignoreCase = true)
        }
    }

    fun canModerate(group: GroupResponse, userId: String?): Boolean {
        val member = memberOf(group, userId) ?: return false
        return when (member.role.lowercase()) {
            GroupRoles.OWNER -> true
            GroupRoles.ADMIN -> rightsOf(member).ban_users || rightsOf(member).delete_messages
            else -> group.settings.perm_moderate.equals(GroupPerms.ALL, ignoreCase = true)
        }
    }

    fun canPin(group: GroupResponse, userId: String?): Boolean {
        val member = memberOf(group, userId) ?: return false
        return when (member.role.lowercase()) {
            GroupRoles.OWNER -> true
            GroupRoles.ADMIN -> rightsOf(member).pin_messages
            else -> group.settings.perm_pin.equals(GroupPerms.ALL, ignoreCase = true)
        }
    }

    fun canSend(group: GroupResponse, userId: String?): Boolean {
        val member = memberOf(group, userId) ?: return false
        return when (member.role.lowercase()) {
            GroupRoles.OWNER -> true
            GroupRoles.ADMIN -> rightsOf(member).post_messages
            else -> group.settings.perm_send_messages.equals(GroupPerms.ALL, ignoreCase = true)
        }
    }

    fun canAddAdmins(group: GroupResponse, userId: String?): Boolean {
        val member = memberOf(group, userId) ?: return false
        return when (member.role.lowercase()) {
            GroupRoles.OWNER -> true
            GroupRoles.ADMIN -> rightsOf(member).add_admins
            else -> false
        }
    }

    fun rightsOf(member: GroupMemberDto): AdminRightsDto {
        return when (member.role.lowercase()) {
            GroupRoles.OWNER -> AdminRightsDto.OWNER
            GroupRoles.ADMIN -> member.admin_rights ?: AdminRightsDto.DEFAULT_ADMIN
            else -> AdminRightsDto()
        }
    }
}

object GroupPerms {
    const val ADMIN = "admin"
    const val ALL = "all"
    const val NONE = "none"
}

fun parseInviteToken(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val withoutScheme = trimmed.removePrefix("https://").removePrefix("http://")
    val path = withoutScheme.substringAfter("glagolitsa.app/", missingDelimiterValue = "")
    val fromHost = when {
        path.startsWith("join/") -> path.removePrefix("join/")
        else -> ""
    }
    val token = fromHost.ifBlank {
        if (trimmed.startsWith("join/")) trimmed.removePrefix("join/") else trimmed
    }.trim().trim('/')
    if (token.isEmpty() || token.contains('/') || token.contains(' ')) return null
    if (token.length < 8) return null
    return token
}

fun inviteLinkForToken(token: String): String = "https://glagolitsa.app/join/$token"
