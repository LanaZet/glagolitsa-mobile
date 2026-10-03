// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatPermissionPolicyTest {
    @Test
    fun ownerHasEveryRight() {
        val group = group(me = member("me", GroupRoles.OWNER))
        assertTrue(ChatPermissionPolicy.canInvite(group, "me"))
        assertTrue(ChatPermissionPolicy.canChangeInfo(group, "me"))
        assertTrue(ChatPermissionPolicy.canModerate(group, "me"))
        assertTrue(ChatPermissionPolicy.canSend(group, "me"))
        assertTrue(ChatPermissionPolicy.canAddAdmins(group, "me"))
    }

    @Test
    fun memberFollowsDefaultPerms() {
        val closed = group(
            me = member("me", GroupRoles.MEMBER),
            settings = GroupSettingsDto(perm_invite = GroupPerms.ADMIN, perm_send_messages = GroupPerms.ALL),
        )
        assertFalse(ChatPermissionPolicy.canInvite(closed, "me"))
        assertTrue(ChatPermissionPolicy.canSend(closed, "me"))
    }

    @Test
    fun adminUsesRightsFlags() {
        val group = group(
            me = member(
                "me",
                GroupRoles.ADMIN,
                AdminRightsDto.DEFAULT_ADMIN.copy(invite_users = false, post_messages = true),
            ),
        )
        assertFalse(ChatPermissionPolicy.canInvite(group, "me"))
        assertTrue(ChatPermissionPolicy.canSend(group, "me"))
    }

    @Test
    fun parseInviteToken_acceptsFullLinkAndBareToken() {
        assertEquals("aabbccddeeff0011", parseInviteToken("https://glagolitsa.app/join/aabbccddeeff0011"))
        assertEquals("aabbccddeeff0011", parseInviteToken("aabbccddeeff0011"))
        assertNull(parseInviteToken("short"))
        assertNull(parseInviteToken(""))
    }
}

private fun member(id: String, role: String, rights: AdminRightsDto? = null) = GroupMemberDto(
    user_id = id,
    role = role,
    admin_rights = rights,
)

private fun group(
    me: GroupMemberDto,
    settings: GroupSettingsDto = GroupSettingsDto(),
) = GroupResponse(
    group_id = "g",
    title = "Team",
    members = listOf(me),
    settings = settings,
)
