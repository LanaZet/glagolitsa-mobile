// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.Message
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ChatMessageBubbleThreadPreviewTest {
    @Test
    fun threadPreviewIsHiddenWhenSummaryIsMissing() = runComposeUiTest {
        setContent {
            ChatMessageBubble(
                message = message(),
                currentUserId = "me",
                chat = chat(),
                allowThreadReply = true,
                onThreadBranchClick = {},
            )
        }

        onAllNodesWithText("Ветка").assertCountEquals(0)
        onAllNodesWithText("2 ответа").assertCountEquals(0)
    }

    @Test
    fun threadPreviewShowsReplyCountWhenSummaryExists() = runComposeUiTest {
        setContent {
            ChatMessageBubble(
                message = message(),
                currentUserId = "me",
                chat = chat(),
                threadBranchPreview = ChatThreadBranchPreviewData(
                    replyCount = 2,
                    lastSenderName = "Bob",
                    lastBody = "latest reply",
                ),
                allowThreadReply = true,
                onThreadBranchClick = {},
            )
        }

        // Title and count are separate nodes (not "Ветка · 2 ответа").
        onNodeWithText("Ветка").assertIsDisplayed()
        onNodeWithText("2 ответа").assertIsDisplayed()
        onNodeWithText("Bob: latest reply").assertIsDisplayed()
    }

    private fun chat(): Chat = Chat(
        id = "chat",
        title = "Team",
        type = ChatType.GROUP,
    )

    private fun message(): Message = Message(
        id = "root",
        chat_id = "chat",
        sender_id = "me",
        body = "root",
        created_at = "2026-07-18T10:00:00Z",
    )
}
