// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UndeliveredMessagePolicyTest {
    private val me = "user-me"
    private val peer = "user-peer"

    @Test
    fun ownFailedAndSending_areUndelivered() {
        val failed = Message(
            id = "f1",
            chat_id = "c1",
            sender_id = me,
            body = "hi",
            status = MessageStatus.FAILED,
        )
        val sending = Message(
            id = "s1",
            chat_id = "c1",
            sender_id = me,
            body = "hi",
            status = MessageStatus.SENDING,
        )
        assertTrue(UndeliveredMessagePolicy.isOwnUndelivered(failed, me))
        assertTrue(UndeliveredMessagePolicy.isOwnUndelivered(sending, me))
    }

    @Test
    fun peerOrDelivered_areNotUndelivered() {
        val peerMsg = Message(id = "p1", chat_id = "c1", sender_id = peer, body = "x", status = MessageStatus.FAILED)
        val sent = Message(id = "s2", chat_id = "c1", sender_id = me, body = "x", status = MessageStatus.SENT)
        assertFalse(UndeliveredMessagePolicy.isOwnUndelivered(peerMsg, me))
        assertFalse(UndeliveredMessagePolicy.isOwnUndelivered(sent, me))
        assertFalse(UndeliveredMessagePolicy.isOwnUndelivered(sent, null))
    }

    @Test
    fun blankBodyWithoutCiphertext_skipped() {
        val empty = Message(id = "e1", chat_id = "c1", sender_id = me, body = "  ", status = MessageStatus.FAILED)
        assertFalse(UndeliveredMessagePolicy.isOwnUndelivered(empty, me))
    }

    @Test
    fun selectForRetry_filtersChatAndOrdersStable() {
        val messages = listOf(
            Message(id = "1", chat_id = "a", sender_id = me, body = "a", status = MessageStatus.FAILED),
            Message(id = "2", chat_id = "b", sender_id = me, body = "b", status = MessageStatus.SENDING),
            Message(id = "3", chat_id = "a", sender_id = peer, body = "c", status = MessageStatus.FAILED),
            Message(id = "4", chat_id = "a", sender_id = me, body = "d", status = MessageStatus.SENT),
        )
        val onlyA = UndeliveredMessagePolicy.selectForRetry(messages, me, chatId = "a")
        assertEquals(listOf("1"), onlyA.map { it.id })
        val all = UndeliveredMessagePolicy.selectForRetry(messages, me)
        assertEquals(listOf("1", "2"), all.map { it.id })
    }

    @Test
    fun retryBannerLabel_ruPlural() {
        assertEquals("", UndeliveredMessagePolicy.retryBannerLabel(0))
        assertEquals("1 сообщение не отправлено", UndeliveredMessagePolicy.retryBannerLabel(1))
        assertEquals("3 сообщений не отправлено", UndeliveredMessagePolicy.retryBannerLabel(3))
    }
}
