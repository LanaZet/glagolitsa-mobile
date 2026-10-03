// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageEditPolicyTest {
    private val me = "user-me"
    private val peer = "user-peer"

    @Test
    fun ownSentText_canEdit() {
        val msg = Message(
            id = "m1",
            chat_id = "c1",
            sender_id = me,
            body = "hello",
            status = MessageStatus.SENT,
        )
        assertTrue(MessageEditPolicy.canEdit(msg, me))
    }

    @Test
    fun foreignMessage_cannotEdit() {
        val msg = Message(id = "m1", chat_id = "c1", sender_id = peer, body = "hello")
        assertFalse(MessageEditPolicy.canEdit(msg, me))
    }

    @Test
    fun sendingOrFailed_cannotEdit() {
        val sending = Message(
            id = "m1",
            chat_id = "c1",
            sender_id = me,
            body = "x",
            status = MessageStatus.SENDING,
        )
        val failed = Message(
            id = "m2",
            chat_id = "c1",
            sender_id = me,
            body = "x",
            status = MessageStatus.FAILED,
        )
        assertFalse(MessageEditPolicy.canEdit(sending, me))
        assertFalse(MessageEditPolicy.canEdit(failed, me))
    }

    @Test
    fun attachmentOnly_cannotEdit() {
        val msg = Message(
            id = "m1",
            chat_id = "c1",
            sender_id = me,
            body = "📎 photo.png",
            status = MessageStatus.SENT,
        )
        assertFalse(MessageEditPolicy.canEdit(msg, me))
    }

    @Test
    fun canCommitEdit_requiresChangeAndNonEmpty() {
        val msg = Message(id = "m1", chat_id = "c1", sender_id = me, body = "hello")
        assertFalse(MessageEditPolicy.canCommitEdit(msg, "hello"))
        assertFalse(MessageEditPolicy.canCommitEdit(msg, "  hello  "))
        assertFalse(MessageEditPolicy.canCommitEdit(msg, "   "))
        assertTrue(MessageEditPolicy.canCommitEdit(msg, "hello world"))
        assertEquals("hello world", MessageEditPolicy.normalizeBody("  hello world  "))
    }

    @Test
    fun actionPolicy_includesEditForOwnSent() {
        val msg = Message(
            id = "m1",
            chat_id = "dm-1",
            sender_id = me,
            body = "edit me",
            status = MessageStatus.SENT,
        )
        val chat = Chat(id = "dm-1", title = "peer", type = ChatType.DIRECT, member_ids = listOf(me, peer))
        val actions = MessageActionPolicy.availableActions(msg, chat, me)
        assertTrue(MessageAction.EDIT in actions)
        assertEquals("Редактировать", MessageActionPolicy.label(MessageAction.EDIT))
    }

    @Test
    fun actionPolicy_hidesEditForPeer() {
        val msg = Message(
            id = "m1",
            chat_id = "dm-1",
            sender_id = peer,
            body = "nope",
            status = MessageStatus.SENT,
        )
        val chat = Chat(id = "dm-1", title = "peer", type = ChatType.DIRECT, member_ids = listOf(me, peer))
        val actions = MessageActionPolicy.availableActions(msg, chat, me)
        assertFalse(MessageAction.EDIT in actions)
    }
}
