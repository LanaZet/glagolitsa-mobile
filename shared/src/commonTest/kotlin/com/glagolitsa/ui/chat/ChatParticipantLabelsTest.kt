// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.GroupMemberDto
import com.glagolitsa.model.GroupRoles
import com.glagolitsa.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatParticipantLabelsTest {
    private val marco = User(
        id = "user-marco",
        username = "marco",
        display_name = "Марко",
        avatar_url = "data:image/png;base64,xx",
    )

    @Test
    fun selfUsesSessionDisplayName() {
        assertEquals(
            "Марко",
            chatMemberDisplayName(
                memberUserId = "user-marco",
                currentUser = marco,
                profile = null,
                cachedUsername = null,
            ),
        )
        assertTrue(isChatMemberSelf("user-marco", marco.id))
        assertEquals("marco", chatMemberUsername("user-marco", marco, null))
        assertEquals("data:image/png;base64,xx", chatMemberAvatarUrl("user-marco", marco, null, null))
    }

    @Test
    fun selfFallsBackToUsername() {
        val unnamed = marco.copy(display_name = null)
        assertEquals(
            "marco",
            chatMemberDisplayName("user-marco", unnamed, null, null),
        )
    }

    @Test
    fun otherUsesProfileThenCache() {
        val other = User(id = "user-bob", username = "bob", display_name = "Боб")
        assertEquals("Боб", chatMemberDisplayName("user-bob", marco, other, null))
        assertEquals("cached", chatMemberDisplayName("user-bob", marco, null, "cached"))
        assertEquals("Пользователь", chatMemberDisplayName("user-bob", marco, null, null))
        assertFalse(isChatMemberSelf("user-bob", marco.id))
        assertNull(chatMemberUsername("user-bob", marco, null))
    }

    @Test
    fun memberListKindMatchesConversationType() {
        assertEquals(
            ChatMemberListKind.Subscribers,
            chatMemberListKind(Chat(id = "channel", title = "News", type = ChatType.CHANNEL)),
        )
        assertEquals(
            ChatMemberListKind.Participants,
            chatMemberListKind(Chat(id = "group", title = "Team", type = ChatType.GROUP)),
        )
        assertNull(chatMemberListKind(Chat(id = "dm", title = "Марко", type = ChatType.DIRECT)))
    }

    @Test
    fun rowPresentationUsesListKindForMemberRoleCopy() {
        val member = GroupMemberDto(user_id = "user-bob", role = GroupRoles.MEMBER)
        assertEquals(
            "подписчик",
            chatMemberRowUiModel(
                member = member,
                currentUser = marco,
                profile = null,
                cachedUsername = "bob",
                cachedAvatarUrl = null,
                kind = ChatMemberListKind.Subscribers,
                nowMs = 0L,
            ).roleLabel,
        )
        assertEquals(
            "участник",
            chatMemberRowUiModel(
                member = member,
                currentUser = marco,
                profile = null,
                cachedUsername = "bob",
                cachedAvatarUrl = null,
                kind = ChatMemberListKind.Participants,
                nowMs = 0L,
            ).roleLabel,
        )
    }
}
