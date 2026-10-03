// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatDeletePolicyTest {

    private fun group(creator: String? = "owner") = Chat(
        id = "g1",
        title = "Семья",
        type = ChatType.GROUP,
        creator_id = creator,
    )

    private fun channel(creator: String? = "owner") = Chat(
        id = "c1",
        title = "Новости",
        type = ChatType.CHANNEL,
        creator_id = creator,
    )

    private fun dm() = Chat(
        id = "d1",
        title = "Аня",
        type = ChatType.DIRECT,
        creator_id = "me",
    )

    @Test
    fun ownerWipesGroupAndChannel() {
        assertEquals(
            ChatDeletePolicy.Action.PermanentDelete,
            ChatDeletePolicy.action(group(), "owner"),
        )
        assertEquals(
            ChatDeletePolicy.Action.PermanentDelete,
            ChatDeletePolicy.action(channel(), "owner"),
        )
    }

    @Test
    fun memberLeaves() {
        assertEquals(
            ChatDeletePolicy.Action.Leave,
            ChatDeletePolicy.action(group(), "alice"),
        )
        assertEquals(
            ChatDeletePolicy.Action.Leave,
            ChatDeletePolicy.action(channel("other"), "owner"),
        )
    }

    @Test
    fun dmHidesLocally() {
        assertEquals(
            ChatDeletePolicy.Action.HideLocally,
            ChatDeletePolicy.action(dm(), "me"),
        )
    }

    @Test
    fun missingOwnerFallsBackToLeave() {
        assertEquals(
            ChatDeletePolicy.Action.Leave,
            ChatDeletePolicy.action(group(creator = null), "owner"),
        )
    }

    @Test
    fun ownerRoleWipesEvenWithoutCreatorId() {
        assertEquals(
            ChatDeletePolicy.Action.PermanentDelete,
            ChatDeletePolicy.action(group(creator = null), "owner", membershipRole = "owner"),
        )
    }

    @Test
    fun copy_permanentMentionsEveryone() {
        val body = ChatDeletePolicy.confirmBody(group(), ChatDeletePolicy.Action.PermanentDelete)
        assertEquals(true, body.contains("у всех"))
        assertEquals("Удалить для всех", ChatDeletePolicy.confirmButton(ChatDeletePolicy.Action.PermanentDelete))
    }
}
