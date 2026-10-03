// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ThreadBranchSummaryTest {
    @Test
    fun ordinaryRepliesDoNotCreateThreadBranchSummary() {
        val parent = message(id = "root")
        val reply = message(
            id = "reply",
            replyToMessageId = parent.id,
            visibility = MESSAGE_VISIBILITY_MAIN,
        )

        val summaries = buildThreadBranchSummaries(
            rootMessages = listOf(parent, reply),
            threadReplies = emptyList(),
        )

        assertFalse(parent.id in summaries)
    }

    @Test
    fun threadOnlyRepliesCreateCountedSummaryForRootMessage() {
        val root = message(id = "root")
        val firstReply = message(
            id = "reply-1",
            body = "first",
            senderId = "alice",
            threadRootId = root.id,
            createdAt = "2026-07-18T10:00:00Z",
            visibility = MESSAGE_VISIBILITY_THREAD_ONLY,
        )
        val lastReply = message(
            id = "reply-2",
            body = "latest",
            senderId = "bob",
            threadRootId = root.id,
            createdAt = "2026-07-18T10:05:00Z",
            visibility = MESSAGE_VISIBILITY_THREAD_ONLY,
        )

        val summary = buildThreadBranchSummaries(
            rootMessages = listOf(root),
            threadReplies = listOf(lastReply, firstReply),
        ).getValue(root.id)

        assertEquals(root.id, summary.rootMessageId)
        assertEquals(2, summary.replyCount)
        assertEquals(lastReply.id, summary.lastReply?.id)
        assertEquals(lastReply.created_at, summary.lastReplyAt)
        assertEquals(lastReply.sender_id, summary.lastReplySenderId)
        assertEquals(listOf(firstReply.sender_id, lastReply.sender_id), summary.participantSenderIds)
    }

    @Test
    fun backendSummaryOverridesLocalCountAndKeepsLocalPreviewWhenAvailable() {
        val root = message(
            id = "root",
            threadReplyCount = 5,
            lastThreadReplyAt = "2026-07-18T10:10:00Z",
            lastThreadReplySenderId = "bob",
        )
        val localReply = message(
            id = "reply-1",
            body = "local preview",
            senderId = "bob",
            threadRootId = root.id,
            createdAt = "2026-07-18T10:10:00Z",
            visibility = MESSAGE_VISIBILITY_THREAD_ONLY,
        )

        val summary = buildThreadBranchSummaries(
            rootMessages = listOf(root),
            threadReplies = listOf(localReply),
        ).getValue(root.id)

        assertEquals(5, summary.replyCount)
        assertEquals("2026-07-18T10:10:00Z", summary.lastReplyAt)
        assertEquals("bob", summary.lastReplySenderId)
        assertEquals("local preview", summary.lastReply?.body)
    }

    private fun message(
        id: String,
        body: String = "body",
        senderId: String = "me",
        replyToMessageId: String? = null,
        threadRootId: String? = null,
        createdAt: String = "2026-07-18T10:00:00Z",
        visibility: String = MESSAGE_VISIBILITY_MAIN,
        threadReplyCount: Int = 0,
        lastThreadReplyAt: String? = null,
        lastThreadReplySenderId: String? = null,
    ): Message = Message(
        id = id,
        chat_id = "chat",
        sender_id = senderId,
        body = body,
        reply_to_message_id = replyToMessageId,
        thread_root_id = threadRootId,
        visibility = visibility,
        created_at = createdAt,
        thread_reply_count = threadReplyCount,
        last_thread_reply_at = lastThreadReplyAt,
        last_thread_reply_sender_id = lastThreadReplySenderId,
    )
}
