// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoriteMessagesLogicTest {
    private val chat = Chat(id = "c1", title = "Peer", type = ChatType.DIRECT, member_ids = listOf("a", "b"))
    private val msg1 = Message(id = "m1", chat_id = chat.id, sender_id = "a", body = "hello", created_at = "2026-07-20T10:00:00Z")
    private val msg2 = Message(id = "m2", chat_id = chat.id, sender_id = "b", body = "📎 file.pdf", created_at = "2026-07-21T11:22:00Z")

    @Test
    fun add_prependsAndDedupes() {
        val first = FavoriteMessagesLogic.add(emptyList(), msg1.id, chat.id, "2026-07-20T10:00:00Z")
        assertEquals(1, first.size)
        assertEquals(msg1.id, first.single().messageId)

        val second = FavoriteMessagesLogic.add(first, msg2.id, chat.id, "2026-07-21T11:22:00Z")
        assertEquals(listOf(msg2.id, msg1.id), second.map { it.messageId })

        val deduped = FavoriteMessagesLogic.add(second, msg1.id, chat.id, "2026-07-22T00:00:00Z")
        assertEquals(second, deduped)
    }

    @Test
    fun remove_dropsMatchingRef() {
        val refs = FavoriteMessagesLogic.add(
            FavoriteMessagesLogic.add(emptyList(), msg1.id, chat.id, "t1"),
            msg2.id,
            chat.id,
            "t2",
        )
        val removed = FavoriteMessagesLogic.remove(refs, msg1.id)
        assertEquals(listOf(msg2.id), removed.map { it.messageId })
        assertEquals(refs, FavoriteMessagesLogic.remove(refs, "missing"))
    }

    @Test
    fun resolve_prunesMissingMessagesAndSortsByAddedAt() {
        val refs = listOf(
            FavoriteMessageRef(msg1.id, chat.id, "2026-07-20T10:00:00Z"),
            FavoriteMessageRef("gone", chat.id, "2026-07-22T00:00:00Z"),
            FavoriteMessageRef(msg2.id, chat.id, "2026-07-21T11:22:00Z"),
        )
        val messages = mapOf(msg1.id to msg1, msg2.id to msg2)
        val chats = mapOf(chat.id to chat)

        val resolved = FavoriteMessagesLogic.resolve(
            refs = refs,
            findMessage = messages::get,
            findChat = chats::get,
            limit = 10,
        )

        assertTrue(resolved.pruned)
        assertEquals(2, resolved.aliveRefs.size)
        assertEquals(listOf(msg2.id, msg1.id), resolved.items.map { it.message.id })
        assertEquals(chat, resolved.items.first().chat)
    }

    @Test
    fun resolve_respectsLimit() {
        val refs = listOf(
            FavoriteMessageRef(msg1.id, chat.id, "2026-07-20T10:00:00Z"),
            FavoriteMessageRef(msg2.id, chat.id, "2026-07-21T11:22:00Z"),
        )
        val resolved = FavoriteMessagesLogic.resolve(
            refs = refs,
            findMessage = mapOf(msg1.id to msg1, msg2.id to msg2)::get,
            findChat = { chat },
            limit = 1,
        )
        assertEquals(1, resolved.items.size)
        assertEquals(msg2.id, resolved.items.single().message.id)
        assertFalse(resolved.pruned)
    }

    @Test
    fun presentation_formatsTextAndFileRows() {
        val text = FavoriteMessagesLogic.presentation(
            FavoriteMessageItem(msg1, chat, "2026-07-20T10:00:00Z"),
        )
        assertEquals("hello", text.body)
        assertEquals("💬", text.icon)
        assertEquals("Peer · 2026-07-20 10:00", text.meta)

        val file = FavoriteMessagesLogic.presentation(
            FavoriteMessageItem(msg2, null, "t"),
        )
        assertEquals("📎 file.pdf", file.body)
        assertEquals("📎", file.icon)
        assertEquals("Чат · 2026-07-21 11:22", file.meta)
    }
}
