// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageReplyPolicyTest {
    @Test
    fun directMessagesDoNotAllowThreadReplies() {
        val chat = Chat(id = "dm-1", title = "Alice", type = ChatType.DIRECT)

        assertFalse(MessageReplyPolicy.canReplyInThread(chat))
    }

    @Test
    fun groupChatsAllowThreadReplies() {
        val chat = Chat(id = "group-1", title = "Team", type = ChatType.GROUP)

        assertTrue(MessageReplyPolicy.canReplyInThread(chat))
    }

    @Test
    fun chatReplyRelationReferencesParentInMainTimeline() {
        val parent = message(id = "message-1")

        val relation = MessageReplyPolicy.chatReplyRelation(parent)

        assertEquals("message-1", relation.replyToMessageId)
        assertEquals("user-1", relation.replyPreviewSenderId)
        assertEquals("hello", relation.replyPreviewBody)
        assertNull(relation.threadRootId)
        assertNull(relation.threadParentId)
        assertEquals(MESSAGE_VISIBILITY_MAIN, relation.visibility)
    }

    @Test
    fun chatReplyRelationUsesSelectedQuoteBodyWhenProvided() {
        val parent = message(id = "message-1")

        val relation = MessageReplyPolicy.chatReplyRelation(
            parentMessage = parent,
            quoteBody = "  only this part  ",
        )

        assertEquals("message-1", relation.replyToMessageId)
        assertEquals("only this part", relation.replyPreviewBody)
    }

    @Test
    fun chatReplyRelationFallsBackToFullBodyWhenQuoteBlank() {
        val parent = message(id = "message-1")

        val relation = MessageReplyPolicy.chatReplyRelation(
            parentMessage = parent,
            quoteBody = "   ",
        )

        assertEquals("hello", relation.replyPreviewBody)
    }

    @Test
    fun threadReplyRelationUsesSelectedQuoteBodyWhenProvided() {
        val root = message(id = "root-1")
        val selectedParent = message(id = "reply-1")

        val relation = MessageReplyPolicy.threadReplyRelation(
            threadRootMessage = root,
            replyToMessage = selectedParent,
            quoteBody = "partial quote",
        )

        assertEquals("partial quote", relation.replyPreviewBody)
        assertEquals("reply-1", relation.replyToMessageId)
    }

    @Test
    fun resolveQuoteBodyPrefersNonBlankQuote() {
        assertEquals("quote", MessageReplyPolicy.resolveQuoteBody("quote", "full"))
        assertEquals("full", MessageReplyPolicy.resolveQuoteBody(null, "full"))
        assertEquals("full", MessageReplyPolicy.resolveQuoteBody("  ", "full"))
    }

    @Test
    fun chatReplyRelationUsesPendingParentReferenceWithSnapshot() {
        val parent = message(id = "server-id", pendingId = "pending-parent")

        val relation = MessageReplyPolicy.chatReplyRelation(parent)

        assertEquals("pending-parent", relation.replyToMessageId)
        assertEquals("user-1", relation.replyPreviewSenderId)
        assertEquals("hello", relation.replyPreviewBody)
    }

    @Test
    fun threadReplyRelationLinksThreadRootAndSelectedParent() {
        val root = message(id = "root-1")
        val selectedParent = message(id = "reply-1")

        val relation = MessageReplyPolicy.threadReplyRelation(
            threadRootMessage = root,
            replyToMessage = selectedParent,
        )

        assertEquals("reply-1", relation.replyToMessageId)
        assertEquals("user-1", relation.replyPreviewSenderId)
        assertEquals("hello", relation.replyPreviewBody)
        assertEquals("root-1", relation.threadRootId)
        assertEquals("reply-1", relation.threadParentId)
        assertEquals(MESSAGE_VISIBILITY_THREAD_ONLY, relation.visibility)
    }

    @Test
    fun threadReplyRelationFallsBackToRootAsParent() {
        val root = message(id = "root-1")

        val relation = MessageReplyPolicy.threadReplyRelation(threadRootMessage = root)

        assertNull(relation.replyToMessageId)
        assertEquals("root-1", relation.threadRootId)
        assertEquals("root-1", relation.threadParentId)
        assertEquals(MESSAGE_VISIBILITY_THREAD_ONLY, relation.visibility)
    }

    @Test
    fun directMessageThreadRelationNormalizesToMainReply() {
        val chat = Chat(id = "dm-1", title = "Alice", type = ChatType.DIRECT)
        val relation = MessageRelationDraft(
            threadRootId = "root-1",
            threadParentId = "reply-1",
            replyPreviewSenderId = "sender-1",
            replyPreviewBody = "quote",
            visibility = MESSAGE_VISIBILITY_THREAD_ONLY,
        )

        val normalized = MessageReplyPolicy.normalizeRelationForChat(chat, relation)

        assertNotNull(normalized)
        assertEquals("reply-1", normalized.replyToMessageId)
        assertEquals("sender-1", normalized.replyPreviewSenderId)
        assertEquals("quote", normalized.replyPreviewBody)
        assertNull(normalized.threadRootId)
        assertNull(normalized.threadParentId)
        assertEquals(MESSAGE_VISIBILITY_MAIN, normalized.visibility)
    }

    @Test
    fun directMessageThreadOnlyWithoutTargetDropsRelation() {
        val chat = Chat(id = "dm-1", title = "Alice", type = ChatType.DIRECT)
        val relation = MessageRelationDraft(visibility = MESSAGE_VISIBILITY_THREAD_ONLY)

        assertNull(MessageReplyPolicy.normalizeRelationForChat(chat, relation))
    }

    @Test
    fun groupThreadRelationPassesThroughEvenIfRootNotLoadedLocally() {
        // Server may still accept thread_root_id that is not in local DB yet.
        val chat = Chat(id = "group-1", title = "Team", type = ChatType.GROUP)
        val relation = MessageRelationDraft(
            replyToMessageId = "orphan-parent",
            threadRootId = "missing-root",
            threadParentId = "orphan-parent",
            visibility = MESSAGE_VISIBILITY_THREAD_ONLY,
        )

        val normalized = MessageReplyPolicy.normalizeRelationForChat(chat, relation)

        assertEquals(relation, normalized)
        assertEquals("missing-root", normalized?.threadRootId)
    }

    @Test
    fun nullRelationStaysNull() {
        val chat = Chat(id = "dm-1", title = "Alice", type = ChatType.DIRECT)
        assertNull(MessageReplyPolicy.normalizeRelationForChat(chat, null))
    }

    @Test
    fun mainReplyOnDirectMessageIsUnchanged() {
        val chat = Chat(id = "dm-1", title = "Alice", type = ChatType.DIRECT)
        val relation = MessageRelationDraft(
            replyToMessageId = "parent-1",
            visibility = MESSAGE_VISIBILITY_MAIN,
        )
        assertEquals(relation, MessageReplyPolicy.normalizeRelationForChat(chat, relation))
    }

    private fun message(id: String, pendingId: String? = null): Message =
        Message(
            id = id,
            chat_id = "chat-1",
            sender_id = "user-1",
            body = "hello",
            pending_id = pendingId,
        )
}
