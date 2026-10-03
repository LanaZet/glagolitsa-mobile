// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatReplyPreviewResolverTest {
    @Test
    fun usesLoadedParentWhenAvailable() {
        val parent = message(id = "parent-1", senderId = "alice", body = "original")
        val reply = message(
            id = "reply-1",
            senderId = "bob",
            body = "answer",
            replyToMessageId = parent.id,
            replyPreviewSenderId = "fallback",
            replyPreviewBody = "stale",
        )

        val preview = resolveInlineReplyPreview(
            message = reply,
            messagesById = listOf(parent, reply).associateBy { it.id },
            currentUserId = "bob",
            senderNameFor = ::senderName,
        )

        assertEquals("Alice", preview?.senderName)
        assertEquals("original", preview?.body)
        assertEquals("parent-1", preview?.targetMessageId)
        assertEquals("alice", preview?.targetSenderId)
    }

    @Test
    fun fallsBackToSealedSnapshotWhenParentIsMissing() {
        val reply = message(
            id = "reply-1",
            senderId = "bob",
            body = "answer",
            replyToMessageId = "missing-parent",
            replyPreviewSenderId = "alice",
            replyPreviewBody = "quoted body",
        )

        val preview = resolveInlineReplyPreview(
            message = reply,
            messagesById = mapOf(reply.id to reply),
            currentUserId = "bob",
            senderNameFor = ::senderName,
        )

        assertEquals("Alice", preview?.senderName)
        assertEquals("quoted body", preview?.body)
        assertEquals("missing-parent", preview?.targetMessageId)
        assertEquals("alice", preview?.targetSenderId)
    }

    @Test
    fun returnsNullWhenParentAndSnapshotAreMissing() {
        val reply = message(
            id = "reply-1",
            senderId = "bob",
            body = "answer",
            replyToMessageId = "missing-parent",
        )

        assertNull(
            resolveInlineReplyPreview(
                message = reply,
                messagesById = mapOf(reply.id to reply),
                currentUserId = "bob",
                senderNameFor = ::senderName,
            ),
        )
    }

    @Test
    fun resolvesPendingParentId() {
        val parent = message(
            id = "server-parent",
            pendingId = "pending-parent",
            senderId = "alice",
            body = "optimistic parent",
        )
        val reply = message(
            id = "reply-1",
            senderId = "bob",
            body = "answer",
            replyToMessageId = "pending-parent",
        )

        val preview = resolveInlineReplyPreview(
            message = reply,
            messagesById = listOf(parent, reply).associateBy { it.id },
            currentUserId = "bob",
            senderNameFor = ::senderName,
        )

        assertEquals("Alice", preview?.senderName)
        assertEquals("optimistic parent", preview?.body)
        assertEquals("server-parent", preview?.targetMessageId)
        assertEquals("alice", preview?.targetSenderId)
    }

    private fun message(
        id: String,
        senderId: String,
        body: String,
        pendingId: String? = null,
        replyToMessageId: String? = null,
        replyPreviewSenderId: String? = null,
        replyPreviewBody: String? = null,
    ): Message = Message(
        id = id,
        chat_id = "chat-1",
        sender_id = senderId,
        body = body,
        pending_id = pendingId,
        reply_to_message_id = replyToMessageId,
        reply_preview_sender_id = replyPreviewSenderId,
        reply_preview_body = replyPreviewBody,
    )

    private fun senderName(senderId: String): String? = when (senderId) {
        "alice" -> "Alice"
        "bob" -> "Bob"
        else -> null
    }
}
