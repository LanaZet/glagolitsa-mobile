// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import com.glagolitsa.parseIsoTimestampMillis

/**
 * Admin view of channel subscribers and Telegram-like restrict/ban actions.
 */
object ChannelSubscriberPolicy {
    data class RestrictDuration(
        val minutes: Int,
        val label: String,
    )

    enum class Action {
        Restrict,
        Unrestrict,
        Kick,
        Ban,
        PromoteAdmin,
        DemoteMember,
        TransferOwner,
    }

    val restrictDurations: List<RestrictDuration> = listOf(
        RestrictDuration(minutes = 60, label = "1 час"),
        RestrictDuration(minutes = 8 * 60, label = "8 часов"),
        RestrictDuration(minutes = 24 * 60, label = "1 день"),
        RestrictDuration(minutes = 7 * 24 * 60, label = "1 неделя"),
        RestrictDuration(minutes = FOREVER_MINUTES, label = "навсегда"),
    )

    const val FOREVER_MINUTES: Int = -1

    /**
     * Channels: subscriber list is admin-only (Telegram-like).
     * Groups, including private: every member can see who is in the chat.
     */
    fun canViewList(
        group: GroupResponse,
        userId: String?,
        listAlwaysVisible: Boolean = false,
    ): Boolean {
        if (listAlwaysVisible) {
            return ChatPermissionPolicy.memberOf(group, userId) != null
        }
        return ChatPermissionPolicy.isAdmin(group, userId)
    }

    fun canModerateTarget(
        group: GroupResponse,
        actorId: String?,
        target: GroupMemberDto,
    ): Boolean {
        if (!ChatPermissionPolicy.canModerate(group, actorId)) return false
        val actor = ChatPermissionPolicy.memberOf(group, actorId) ?: return false
        if (actor.user_id == target.user_id) return false
        val targetRole = target.role.lowercase()
        if (targetRole == GroupRoles.OWNER) return false
        if (targetRole == GroupRoles.ADMIN && actor.role.lowercase() != GroupRoles.OWNER) {
            return false
        }
        return true
    }

    fun canAssignRole(
        group: GroupResponse,
        actorId: String?,
        target: GroupMemberDto,
    ): Boolean {
        val actor = ChatPermissionPolicy.memberOf(group, actorId) ?: return false
        if (actor.user_id == target.user_id) return false
        val targetRole = target.role.lowercase()
        if (targetRole == GroupRoles.OWNER) return false
        if (ChatPermissionPolicy.isOwner(group, actorId)) return true
        if (!ChatPermissionPolicy.canAddAdmins(group, actorId)) return false
        return targetRole == GroupRoles.MEMBER
    }

    fun canOpenActions(
        group: GroupResponse,
        actorId: String?,
        target: GroupMemberDto,
    ): Boolean = canModerateTarget(group, actorId, target) || canAssignRole(group, actorId, target)

    fun isMuted(member: GroupMemberDto, nowMs: Long): Boolean {
        val until = parseIsoTimestampMillis(member.muted_until.orEmpty()) ?: return false
        return until > nowMs
    }

    fun availableActions(
        group: GroupResponse,
        actorId: String?,
        target: GroupMemberDto,
        nowMs: Long,
    ): List<Action> {
        return buildList {
            if (canAssignRole(group, actorId, target)) {
                val targetRole = target.role.lowercase()
                val actorIsOwner = ChatPermissionPolicy.isOwner(group, actorId)
                when (targetRole) {
                    GroupRoles.MEMBER -> {
                        add(Action.PromoteAdmin)
                        if (actorIsOwner) add(Action.TransferOwner)
                    }
                    GroupRoles.ADMIN -> {
                        if (actorIsOwner) {
                            add(Action.DemoteMember)
                            add(Action.TransferOwner)
                        }
                    }
                }
            }
            if (canModerateTarget(group, actorId, target)) {
                add(Action.Restrict)
                if (isMuted(target, nowMs)) add(Action.Unrestrict)
                add(Action.Kick)
                add(Action.Ban)
            }
        }
    }

    fun roleLabel(role: String, asSubscriber: Boolean = true): String = when (role.lowercase()) {
        GroupRoles.OWNER -> "владелец"
        GroupRoles.ADMIN -> "админ"
        else -> if (asSubscriber) "подписчик" else "участник"
    }

    fun subscribersForDisplay(members: List<GroupMemberDto>): List<GroupMemberDto> =
        members.sortedWith(
            compareBy<GroupMemberDto> { roleRank(it.role) }
                .thenBy { it.user_id },
        )

    private fun roleRank(role: String): Int = when (role.lowercase()) {
        GroupRoles.OWNER -> 0
        GroupRoles.ADMIN -> 1
        else -> 2
    }
}
