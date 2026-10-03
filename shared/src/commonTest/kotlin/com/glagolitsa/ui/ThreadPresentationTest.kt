// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import com.glagolitsa.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadPresentationTest {
    @Test
    fun replyWindowShowsNewestRepliesFirstWithHiddenCount() {
        val messages = (1..5).map { index ->
            message(id = "reply-$index", createdAt = "2026-07-18T10:0$index:00Z")
        }

        val window = buildThreadReplyWindow(
            messages = messages,
            showAll = false,
            initialVisibleCount = 3,
        )

        assertEquals(listOf("reply-5", "reply-4", "reply-3"), window.visibleMessages.map { it.id })
        assertEquals(2, window.hiddenCount)
    }

    @Test
    fun replyWindowShowsAllRepliesWhenExpanded() {
        val messages = (1..4).map { index ->
            message(id = "reply-$index", createdAt = "2026-07-18T10:0$index:00Z")
        }

        val window = buildThreadReplyWindow(
            messages = messages,
            showAll = true,
            initialVisibleCount = 2,
        )

        assertEquals(listOf("reply-4", "reply-3", "reply-2", "reply-1"), window.visibleMessages.map { it.id })
        assertEquals(0, window.hiddenCount)
    }

    private fun message(
        id: String,
        createdAt: String,
    ): Message = Message(
        id = id,
        chat_id = "chat",
        sender_id = "user",
        created_at = createdAt,
    )
}
