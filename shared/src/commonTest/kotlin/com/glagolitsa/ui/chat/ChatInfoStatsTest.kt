// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.ChatVisibility
import com.glagolitsa.model.Message
import com.glagolitsa.model.PresenceStatus
import com.glagolitsa.model.UserPresenceView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatInfoStatsTest {
    private val me = "user-me"
    private val peer = "user-peer"

    @Test
    fun dm_canCall_andCountsAttachments() {
        val chat = Chat(
            id = "dm-1",
            title = "bob",
            type = ChatType.DIRECT,
            member_ids = listOf(me, peer),
        )
        val messages = listOf(
            Message(id = "1", chat_id = chat.id, sender_id = peer, body = "hi"),
            Message(id = "2", chat_id = chat.id, sender_id = me, body = "📎 photo.png (12 байт)"),
            Message(id = "3", chat_id = chat.id, sender_id = me, body = "📎 report.pdf (99 байт)"),
        )
        val stats = buildChatInfoStats(
            chat = chat,
            messages = messages,
            presenceStatus = "в сети",
            presenceMap = emptyMap(),
            currentUserId = me,
            partnerUserId = peer,
        )
        assertTrue(stats.canCall)
        assertEquals(1, stats.mediaCount)
        assertEquals(1, stats.documentCount)
        assertEquals("в сети", stats.presenceLabel)
    }

    @Test
    fun group_canCall_onlineCountFromPresence() {
        val chat = Chat(
            id = "g-1",
            title = "Team",
            type = ChatType.GROUP,
            member_ids = listOf(me, peer, "u3"),
        )
        val presence = mapOf(
            peer to UserPresenceView(user_id = peer, status = PresenceStatus.ONLINE),
            "u3" to UserPresenceView(user_id = "u3", status = PresenceStatus.IN_CALL, in_call = true),
        )
        val stats = buildChatInfoStats(
            chat = chat,
            messages = emptyList(),
            presenceStatus = null,
            presenceMap = presence,
            currentUserId = me,
            partnerUserId = null,
        )
        assertTrue(stats.canCall)
        assertEquals(3, stats.memberCount)
        assertEquals(2, stats.onlineCount)
        assertTrue(stats.presenceLabel.contains("3 участника"))
        assertTrue(stats.presenceLabel.contains("2 в сети"))
    }

    @Test
    fun twoPersonGroup_canCallOtherMember_evenWithoutMappedPartner() {
        val chat = Chat(
            id = "g-2",
            title = "Тестовый чат",
            type = ChatType.GROUP,
            member_ids = listOf(me, peer),
        )
        val stats = buildChatInfoStats(
            chat = chat,
            messages = emptyList(),
            presenceStatus = null,
            presenceMap = emptyMap(),
            currentUserId = me,
            partnerUserId = null,
        )
        assertTrue(stats.canCall)
        assertEquals(peer, resolveChatCallPartnerId(chat, me, mappedPartnerId = null))
        assertEquals(0, stats.mediaCount)
        assertEquals(0, stats.documentCount)
    }

    @Test
    fun publicChannel_showsSlugNotMockMedia() {
        val chat = Chat(
            id = "ch-1",
            title = "News",
            type = ChatType.CHANNEL,
            visibility = ChatVisibility.PUBLIC,
            slug = "news",
            member_ids = listOf(me),
        )
        val stats = buildChatInfoStats(
            chat = chat,
            messages = emptyList(),
            presenceStatus = null,
            presenceMap = emptyMap(),
            currentUserId = me,
            partnerUserId = null,
        )
        assertEquals(0, stats.mediaCount)
        assertEquals(0, stats.documentCount)
        assertEquals("news", stats.publicSlug)
        assertTrue(stats.presenceLabel.contains("@news"))
        assertFalse(stats.canCall)
    }

    @Test
    fun channelParticipantLabels_useActualSubscriberCount() {
        val chat = Chat(
            id = "ch-one",
            title = "zcoi",
            type = ChatType.CHANNEL,
            visibility = ChatVisibility.PUBLIC,
            slug = "zcoi",
            member_ids = listOf(me),
        )
        val stats = buildChatInfoStats(
            chat = chat,
            messages = emptyList(),
            presenceStatus = null,
            presenceMap = emptyMap(),
            currentUserId = me,
            partnerUserId = null,
        )

        assertEquals(1, stats.memberCount)
        assertEquals("@zcoi · 1 подп.", stats.presenceLabel)
        assertEquals("1 подписчик", chatHeaderParticipantSubtitle(chat, emptyMap()))
    }

    @Test
    fun emptyChannelMembership_doesNotInventTwoParticipants() {
        val chat = Chat(
            id = "ch-empty",
            title = "stale",
            type = ChatType.CHANNEL,
            member_ids = emptyList(),
        )

        assertEquals(0, chatParticipantCount(chat))
        assertEquals(null, chatHeaderParticipantSubtitle(chat, emptyMap()))
    }
}
