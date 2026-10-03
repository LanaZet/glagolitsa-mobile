// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatPresenceTargetsTest {
    @Test
    fun dmUsesResolvedPartnerId() {
        val chat = Chat(
            id = "dm-1",
            title = "Bob",
            type = ChatType.DIRECT,
            member_ids = listOf("alice", "wrong"),
        )

        assertEquals(
            listOf("bob"),
            chatPresenceTargetIds(chat, currentUserId = "alice", dmPartnerId = "bob"),
        )
    }

    @Test
    fun groupUsesOtherMembersOnce() {
        val chat = Chat(
            id = "group-1",
            title = "Team",
            type = ChatType.GROUP,
            member_ids = listOf("alice", "bob", "bob", "carol"),
        )

        assertEquals(
            listOf("bob", "carol"),
            chatPresenceTargetIds(chat, currentUserId = "alice", dmPartnerId = null),
        )
    }
}

