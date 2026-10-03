// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelSubscriberPolicyTest {
    private val owner = GroupMemberDto(user_id = "owner", role = GroupRoles.OWNER)
    private val admin = GroupMemberDto(user_id = "admin", role = GroupRoles.ADMIN)
    private val sub = GroupMemberDto(user_id = "sub", role = GroupRoles.MEMBER)
    private val muted = GroupMemberDto(
        user_id = "muted",
        role = GroupRoles.MEMBER,
        muted_until = "2099-01-01T00:00:00Z",
    )

    private val group = GroupResponse(
        group_id = "ch",
        title = "zcoi",
        members = listOf(owner, admin, sub, muted),
    )

    @Test
    fun onlyAdminSeesList() {
        assertTrue(ChannelSubscriberPolicy.canViewList(group, "owner"))
        assertTrue(ChannelSubscriberPolicy.canViewList(group, "admin"))
        assertFalse(ChannelSubscriberPolicy.canViewList(group, "sub"))
        assertFalse(ChannelSubscriberPolicy.canViewList(group, null))
    }

    @Test
    fun groupMembersSeeListEvenWhenPrivate() {
        assertTrue(ChannelSubscriberPolicy.canViewList(group, "sub", listAlwaysVisible = true))
        assertTrue(ChannelSubscriberPolicy.canViewList(group, "owner", listAlwaysVisible = true))
        assertFalse(ChannelSubscriberPolicy.canViewList(group, null, listAlwaysVisible = true))
        assertFalse(ChannelSubscriberPolicy.canViewList(group, "stranger", listAlwaysVisible = true))
    }

    @Test
    fun groupMemberRoleLabelIsParticipant() {
        assertEquals("участник", ChannelSubscriberPolicy.roleLabel(GroupRoles.MEMBER, asSubscriber = false))
        assertEquals("подписчик", ChannelSubscriberPolicy.roleLabel(GroupRoles.MEMBER, asSubscriber = true))
        assertEquals("владелец", ChannelSubscriberPolicy.roleLabel(GroupRoles.OWNER, asSubscriber = false))
    }

    @Test
    fun cannotModerateSelfOwnerOrPeerAdmin() {
        assertFalse(ChannelSubscriberPolicy.canModerateTarget(group, "owner", owner))
        assertFalse(ChannelSubscriberPolicy.canModerateTarget(group, "admin", owner))
        assertFalse(ChannelSubscriberPolicy.canModerateTarget(group, "admin", admin))
        assertTrue(ChannelSubscriberPolicy.canModerateTarget(group, "owner", admin))
        assertTrue(ChannelSubscriberPolicy.canModerateTarget(group, "owner", sub))
        assertTrue(ChannelSubscriberPolicy.canModerateTarget(group, "admin", sub))
        assertFalse(ChannelSubscriberPolicy.canModerateTarget(group, "sub", sub))
    }

    @Test
    fun ownerCanAssignAdminAndTransfer() {
        val now = 1_700_000_000_000L
        val forSub = ChannelSubscriberPolicy.availableActions(group, "owner", sub, now)
        assertTrue(ChannelSubscriberPolicy.Action.PromoteAdmin in forSub)
        assertTrue(ChannelSubscriberPolicy.Action.TransferOwner in forSub)
        assertFalse(ChannelSubscriberPolicy.Action.DemoteMember in forSub)

        val forAdmin = ChannelSubscriberPolicy.availableActions(group, "owner", admin, now)
        assertTrue(ChannelSubscriberPolicy.Action.DemoteMember in forAdmin)
        assertTrue(ChannelSubscriberPolicy.Action.TransferOwner in forAdmin)
        assertFalse(ChannelSubscriberPolicy.Action.PromoteAdmin in forAdmin)
    }

    @Test
    fun adminCannotChangePeerAdminOrTakeOwnership() {
        val now = 1_700_000_000_000L
        val privilegedAdmin = GroupMemberDto(
            user_id = "admin-plus",
            role = GroupRoles.ADMIN,
            admin_rights = AdminRightsDto.DEFAULT_ADMIN.copy(add_admins = true),
        )
        val withRights = group.copy(members = group.members + privilegedAdmin)

        val forSub = ChannelSubscriberPolicy.availableActions(withRights, "admin-plus", sub, now)
        assertTrue(ChannelSubscriberPolicy.Action.PromoteAdmin in forSub)
        assertFalse(ChannelSubscriberPolicy.Action.TransferOwner in forSub)

        val defaultAdminForSub = ChannelSubscriberPolicy.availableActions(group, "admin", sub, now)
        assertFalse(ChannelSubscriberPolicy.Action.PromoteAdmin in defaultAdminForSub)

        val forAdmin = ChannelSubscriberPolicy.availableActions(withRights, "admin-plus", admin, now)
        assertTrue(forAdmin.none { it == ChannelSubscriberPolicy.Action.DemoteMember })
        assertFalse(ChannelSubscriberPolicy.canOpenActions(group, "admin", admin))
        assertTrue(ChannelSubscriberPolicy.canOpenActions(group, "owner", admin))
    }

    @Test
    fun mutedAddsUnrestrictAction() {
        val now = 1_700_000_000_000L
        val actions = ChannelSubscriberPolicy.availableActions(group, "owner", muted, now)
        assertTrue(ChannelSubscriberPolicy.Action.Unrestrict in actions)
        assertTrue(ChannelSubscriberPolicy.Action.Restrict in actions)
        assertTrue(ChannelSubscriberPolicy.Action.Kick in actions)
        assertTrue(ChannelSubscriberPolicy.Action.Ban in actions)
    }

    @Test
    fun displayOrderOwnerThenAdminThenSubscribers() {
        val ordered = ChannelSubscriberPolicy.subscribersForDisplay(group.members)
        assertEquals(listOf("owner", "admin", "muted", "sub"), ordered.map { it.user_id })
    }
}
