// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatMessageGroupingTest {
    @Test
    fun singleMessage_hasSinglePosition() {
        val messages = listOf(msg("m1", sender = "me"))

        assertEquals(ChatBubbleGroupPosition.Single, chatBubbleGroupPosition(messages, 0))
    }

    @Test
    fun consecutiveMessagesFromSameSender_formGroup() {
        val messages = listOf(
            msg("m1", sender = "me"),
            msg("m2", sender = "me"),
            msg("m3", sender = "me"),
        )

        assertEquals(ChatBubbleGroupPosition.First, chatBubbleGroupPosition(messages, 0))
        assertEquals(ChatBubbleGroupPosition.Middle, chatBubbleGroupPosition(messages, 1))
        assertEquals(ChatBubbleGroupPosition.Last, chatBubbleGroupPosition(messages, 2))
    }

    @Test
    fun senderChange_breaksGroup() {
        val messages = listOf(
            msg("m1", sender = "me"),
            msg("m2", sender = "you"),
            msg("m3", sender = "you"),
        )

        assertEquals(ChatBubbleGroupPosition.Single, chatBubbleGroupPosition(messages, 0))
        assertEquals(ChatBubbleGroupPosition.First, chatBubbleGroupPosition(messages, 1))
        assertEquals(ChatBubbleGroupPosition.Last, chatBubbleGroupPosition(messages, 2))
    }

    @Test
    fun systemMessage_breaksGroup() {
        val messages = listOf(
            msg("m1", sender = "me"),
            msg("sys", sender = "system"),
            msg("m2", sender = "me"),
        )

        assertEquals(ChatBubbleGroupPosition.Single, chatBubbleGroupPosition(messages, 0))
        assertEquals(ChatBubbleGroupPosition.Single, chatBubbleGroupPosition(messages, 1))
        assertEquals(ChatBubbleGroupPosition.Single, chatBubbleGroupPosition(messages, 2))
    }

    private fun msg(id: String, sender: String) = Message(
        id = id,
        chat_id = "chat",
        sender_id = sender,
        body = id,
    )
}
