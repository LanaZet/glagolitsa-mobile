// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagePinPolicyTest {
    @Test
    fun referenceId_prefersStableMessageId() {
        val message = Message(
            id = "server-1",
            chat_id = "chat-1",
            sender_id = "me",
            body = "hello",
            pending_id = "pending-1",
        )

        assertEquals("server-1", MessagePinPolicy.referenceId(message))
    }

    @Test
    fun referenceId_fallsBackToPendingId() {
        val message = Message(
            id = "",
            chat_id = "chat-1",
            sender_id = "me",
            body = "hello",
            pending_id = "pending-1",
        )

        assertEquals("pending-1", MessagePinPolicy.referenceId(message))
    }

    @Test
    fun candidateIds_matchStableOrPendingId() {
        val message = Message(
            id = "server-1",
            chat_id = "chat-1",
            sender_id = "me",
            body = "hello",
            pending_id = "pending-1",
        )

        assertEquals(setOf("server-1", "pending-1"), MessagePinPolicy.candidateIds(message))
        assertTrue(MessagePinPolicy.matches(message, "server-1"))
        assertTrue(MessagePinPolicy.matches(message, "pending-1"))
        assertFalse(MessagePinPolicy.matches(message, "other"))
    }
}
