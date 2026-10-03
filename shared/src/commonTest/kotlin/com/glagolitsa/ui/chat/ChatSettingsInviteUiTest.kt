// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.ChatVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatSettingsInviteUiTest {
    private val creatorChannel = Chat(
        id = "c1",
        title = "hoi",
        type = ChatType.CHANNEL,
        visibility = ChatVisibility.PUBLIC,
        slug = "hoi",
        creator_id = "polo",
        member_ids = listOf("polo", "alice"),
    )

    @Test
    fun inviteLink_publicChannelUsesSlug() {
        assertEquals("https://glagolitsa.app/c/hoi", ChatSettingsInviteUi.inviteLinkLabel(creatorChannel))
    }

    @Test
    fun creator_seesAdminTools_notAudience() {
        assertTrue(ChatSettingsInviteUi.isCreator(creatorChannel, "polo"))
        assertTrue(ChatSettingsInviteUi.showInviteSection(creatorChannel, "polo"))
        assertTrue(ChatSettingsInviteUi.showDeleteAction(creatorChannel, "polo"))
        assertFalse(ChatSettingsInviteUi.showAudienceActions(creatorChannel, "polo"))
        assertFalse(ChatSettingsInviteUi.showDeleteAction(creatorChannel, "alice"))
    }

    @Test
    fun thirdParty_seesOnlyAudience_notAdminTools() {
        assertFalse(ChatSettingsInviteUi.isCreator(creatorChannel, "alice"))
        assertFalse(ChatSettingsInviteUi.showInviteSection(creatorChannel, "alice"))
        assertTrue(ChatSettingsInviteUi.showAudienceActions(creatorChannel, "alice"))
        assertTrue(ChatSettingsInviteUi.showFollowActions(creatorChannel, "alice"))
        assertTrue(ChatSettingsInviteUi.showLeaveAction(creatorChannel, "alice"))
        assertFalse(ChatSettingsInviteUi.showJoinAction(creatorChannel, "alice"))
    }

    @Test
    fun stranger_notMember_seesJoin() {
        val open = creatorChannel.copy(member_ids = listOf("polo"))
        assertTrue(ChatSettingsInviteUi.showJoinAction(open, "bob", openedFromMembership = false))
        assertFalse(ChatSettingsInviteUi.showLeaveAction(open, "bob", openedFromMembership = false))
    }

    @Test
    fun dm_hidesMultiPartySections() {
        val dm = Chat(id = "d", title = "alice", type = ChatType.DIRECT)
        assertFalse(ChatSettingsInviteUi.showInviteSection(dm, "x"))
        assertFalse(ChatSettingsInviteUi.showAudienceActions(dm, "x"))
        assertEquals("Личный чат", ChatSettingsInviteUi.chatKindLabel(dm))
    }

    @Test
    fun ownerRole_describesTelegramRights() {
        val group = Chat(id = "g", title = "GroupTest", type = ChatType.GROUP)
        assertEquals("владелец", ChatSettingsInviteUi.membershipRoleLabel(group, "owner"))
        assertTrue(
            ChatSettingsInviteUi.membershipRoleDescription(group, "owner").contains("владелец"),
        )
        assertTrue(
            ChatSettingsInviteUi.membershipRoleDescription(group, "member").contains("участник"),
        )
    }

    @Test
    fun unknownCreator_treatedAsThirdParty() {
        val orphan = creatorChannel.copy(creator_id = null)
        assertFalse(ChatSettingsInviteUi.showInviteSection(orphan, "polo"))
        assertTrue(ChatSettingsInviteUi.showAudienceActions(orphan, "polo"))
    }

    @Test
    fun ownerRole_showsDeleteEvenWithoutCreatorId() {
        val orphan = creatorChannel.copy(creator_id = null)
        assertTrue(ChatSettingsInviteUi.showDeleteAction(orphan, "polo", membershipRole = "owner"))
        assertFalse(ChatSettingsInviteUi.showAudienceActions(orphan, "polo", membershipRole = "owner"))
        assertFalse(ChatSettingsInviteUi.showLeaveAction(orphan, "polo", membershipRole = "owner"))
        assertTrue(ChatSettingsInviteUi.showRemoveAction(orphan, "polo", membershipRole = "owner"))
    }

    @Test
    fun dm_showsLocalHideAction() {
        val dm = Chat(id = "d", title = "alice", type = ChatType.DIRECT)
        assertTrue(ChatSettingsInviteUi.showHideLocalAction(dm))
        assertTrue(ChatSettingsInviteUi.showRemoveAction(dm, "x"))
        assertFalse(ChatSettingsInviteUi.showDeleteAction(dm, "x"))
        assertFalse(ChatSettingsInviteUi.showLeaveAction(dm, "x"))
    }
}
