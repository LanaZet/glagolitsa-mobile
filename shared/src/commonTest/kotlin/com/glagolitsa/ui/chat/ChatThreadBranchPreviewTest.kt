// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.MESSAGE_VISIBILITY_THREAD_ONLY
import com.glagolitsa.model.Message
import com.glagolitsa.model.ThreadBranchSummary
import com.glagolitsa.model.formatMessageTime
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatThreadBranchPreviewTest {
    @Test
    fun emptySummariesDoNotCreateThreadBranchPreview() {
        val previews = buildChatThreadBranchPreviews(
            summaries = emptyMap(),
            currentUserId = "me",
            senderNameFor = { "Alice" },
        )

        assertEquals(emptyMap(), previews)
    }

    @Test
    fun summaryMapsToPresentationData() {
        val rootId = "root"
        val lastReply = message(
            id = "reply-2",
            body = "latest",
            senderId = "bob",
            threadRootId = rootId,
            createdAt = "2026-07-18T10:05:00Z",
            visibility = MESSAGE_VISIBILITY_THREAD_ONLY,
        )

        val previews = buildChatThreadBranchPreviews(
            summaries = mapOf(
                rootId to ThreadBranchSummary(
                    rootMessageId = rootId,
                    replyCount = 2,
                    lastReplyAt = lastReply.created_at,
                    lastReplySenderId = lastReply.sender_id,
                    lastReply = lastReply,
                    participantSenderIds = listOf("bob", "anna"),
                ),
            ),
            currentUserId = "me",
            senderNameFor = { senderId ->
                when (senderId) {
                    "bob" -> "Bob"
                    "anna" -> "Anna"
                    else -> "Alice"
                }
            },
        )

        val preview = previews.getValue(rootId)
        assertEquals(2, preview.replyCount)
        assertEquals("Bob", preview.lastSenderName)
        assertEquals("latest", preview.lastBody)
        assertEquals(formatMessageTime(lastReply.created_at), preview.lastActivityLabel)
        assertEquals(listOf("Bob", "Anna"), preview.participantLabels)
        assertEquals(true, preview.hasUnread)
    }

    @Test
    fun backendOnlySummaryMapsWithoutLastBody() {
        val rootId = "root"

        val previews = buildChatThreadBranchPreviews(
            summaries = mapOf(
                rootId to ThreadBranchSummary(
                    rootMessageId = rootId,
                    replyCount = 3,
                    lastReplyAt = "2026-07-18T10:05:00Z",
                    lastReplySenderId = "bob",
                ),
            ),
            currentUserId = "me",
            senderNameFor = { senderId -> if (senderId == "bob") "Bob" else null },
        )

        val preview = previews.getValue(rootId)
        assertEquals(3, preview.replyCount)
        assertEquals("Bob", preview.lastSenderName)
        assertEquals(null, preview.lastBody)
        assertEquals(formatMessageTime("2026-07-18T10:05:00Z"), preview.lastActivityLabel)
        assertEquals(listOf("Bob"), preview.participantLabels)
        assertEquals(true, preview.hasUnread)
    }

    @Test
    fun ownLastReplyDoesNotMarkThreadUnread() {
        val rootId = "root"

        val previews = buildChatThreadBranchPreviews(
            summaries = mapOf(
                rootId to ThreadBranchSummary(
                    rootMessageId = rootId,
                    replyCount = 1,
                    lastReplyAt = "2026-07-18T10:05:00Z",
                    lastReplySenderId = "me",
                ),
            ),
            currentUserId = "me",
            senderNameFor = { "Me" },
        )

        val preview = previews.getValue(rootId)
        assertEquals(false, preview.hasUnread)
    }

    private fun message(
        id: String,
        body: String,
        senderId: String = "me",
        threadRootId: String? = null,
        createdAt: String = "2026-07-18T10:00:00Z",
        visibility: String = MESSAGE_VISIBILITY_THREAD_ONLY,
    ): Message = Message(
        id = id,
        chat_id = "chat",
        sender_id = senderId,
        body = body,
        thread_root_id = threadRootId,
        visibility = visibility,
        created_at = createdAt,
    )
}
