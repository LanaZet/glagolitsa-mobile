// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplyQuoteInteractionTest {

    @Test
    fun messageMenu_neverOpensOnQuoteRegions() {
        val quoteRegions = listOf(
            ReplyQuoteTapRegion.QuoteStrip,
            ReplyQuoteTapRegion.QuotePreviewCard,
            ReplyQuoteTapRegion.QuotePreviewBody,
            ReplyQuoteTapRegion.QuoteGoButton,
            ReplyQuoteTapRegion.QuoteSelectionMenu,
        )
        quoteRegions.forEach { region ->
            assertFalse(
                ReplyQuoteInteraction.shouldOpenMessageActionMenu(region),
                "Message action menu must not open for $region",
            )
            assertTrue(
                ReplyQuoteInteraction.consumesBubbleClick(region),
                "Quote UI must consume bubble click for $region",
            )
        }
    }

    @Test
    fun messageMenu_opensOnlyOnMessageBody() {
        assertTrue(
            ReplyQuoteInteraction.shouldOpenMessageActionMenu(ReplyQuoteTapRegion.MessageBody),
        )
        assertFalse(
            ReplyQuoteInteraction.consumesBubbleClick(ReplyQuoteTapRegion.MessageBody),
        )
    }

    @Test
    fun quoteClick_revealsPreviewAndGo() {
        val next = ReplyQuoteInteraction.onQuoteClick(
            current = ReplyQuoteUiState.Idle,
            body = "hello quote",
            targetMessageId = "parent-1",
        )
        assertEquals(ReplyQuoteUiState.PreviewWithGo, next)
        assertTrue(ReplyQuoteInteraction.showsPreview(next))
        assertTrue(ReplyQuoteInteraction.showsGoControl(next, "parent-1"))
    }

    @Test
    fun quoteClick_secondTap_collapsesPreview() {
        val open = ReplyQuoteInteraction.onQuoteClick(
            current = ReplyQuoteUiState.Idle,
            body = "hello",
            targetMessageId = "p1",
        )
        val closed = ReplyQuoteInteraction.onQuoteClick(
            current = open,
            body = "hello",
            targetMessageId = "p1",
        )
        assertEquals(ReplyQuoteUiState.Idle, closed)
    }

    @Test
    fun quoteClick_withoutBodyOrTarget_staysIdle() {
        val next = ReplyQuoteInteraction.onQuoteClick(
            current = ReplyQuoteUiState.Idle,
            body = "",
            targetMessageId = null,
        )
        assertEquals(ReplyQuoteUiState.Idle, next)
        assertFalse(ReplyQuoteInteraction.showsGoControl(next, null))
    }

    @Test
    fun quoteClick_withBodyOnly_showsPreviewWithoutGo() {
        val next = ReplyQuoteInteraction.onQuoteClick(
            current = ReplyQuoteUiState.Idle,
            body = "snapshot only",
            targetMessageId = null,
        )
        assertEquals(ReplyQuoteUiState.PreviewWithGo, next)
        assertTrue(ReplyQuoteInteraction.showsPreview(next))
        assertFalse(ReplyQuoteInteraction.showsGoControl(next, null))
    }

    @Test
    fun goClick_returnsToIdle() {
        assertEquals(ReplyQuoteUiState.Idle, ReplyQuoteInteraction.onGoClick())
    }

    @Test
    fun selectionEnabled_onlyInPreview() {
        assertFalse(ReplyQuoteInteraction.selectionEnabled(ReplyQuoteUiState.Idle))
        assertTrue(ReplyQuoteInteraction.selectionEnabled(ReplyQuoteUiState.PreviewWithGo))
    }

    @Test
    fun selectionMenu_includesCopyAlwaysAndReplyWhenTargetKnown() {
        val idle = ReplyQuoteInteraction.selectionMenuActions(ReplyQuoteUiState.Idle, "p1")
        assertTrue(idle.isEmpty())

        val openWithTarget = ReplyQuoteInteraction.selectionMenuActions(
            ReplyQuoteUiState.PreviewWithGo,
            "parent-1",
        )
        assertEquals(
            listOf(ReplyQuoteSelectionAction.Copy, ReplyQuoteSelectionAction.Reply),
            openWithTarget,
        )

        val openNoTarget = ReplyQuoteInteraction.selectionMenuActions(
            ReplyQuoteUiState.PreviewWithGo,
            null,
        )
        assertEquals(listOf(ReplyQuoteSelectionAction.Copy), openNoTarget)
    }

    @Test
    fun canReplyFromSelection_requiresOpenPreviewTargetAndNonBlankText() {
        assertFalse(
            ReplyQuoteInteraction.canReplyFromSelection(
                state = ReplyQuoteUiState.Idle,
                selectedText = "hi",
                targetMessageId = "p1",
            ),
        )
        assertFalse(
            ReplyQuoteInteraction.canReplyFromSelection(
                state = ReplyQuoteUiState.PreviewWithGo,
                selectedText = "  ",
                targetMessageId = "p1",
            ),
        )
        assertFalse(
            ReplyQuoteInteraction.canReplyFromSelection(
                state = ReplyQuoteUiState.PreviewWithGo,
                selectedText = "hi",
                targetMessageId = null,
            ),
        )
        assertTrue(
            ReplyQuoteInteraction.canReplyFromSelection(
                state = ReplyQuoteUiState.PreviewWithGo,
                selectedText = "hi",
                targetMessageId = "p1",
            ),
        )
    }

    @Test
    fun resolveSelectionReply_trimsAndBuildsPayload() {
        val reply = ReplyQuoteInteraction.resolveSelectionReply(
            state = ReplyQuoteUiState.PreviewWithGo,
            selectedText = "  partial quote  ",
            targetMessageId = "parent-1",
            targetSenderId = "alice",
            previewBody = "full original body",
        )
        assertNotNull(reply)
        assertEquals("parent-1", reply.targetMessageId)
        assertEquals("alice", reply.targetSenderId)
        assertEquals("partial quote", reply.selectedText)
        assertEquals("full original body", reply.previewBody)
    }

    @Test
    fun resolveSelectionReply_rejectsWhenNotInPreview() {
        assertNull(
            ReplyQuoteInteraction.resolveSelectionReply(
                state = ReplyQuoteUiState.Idle,
                selectedText = "x",
                targetMessageId = "p1",
                targetSenderId = "a",
                previewBody = "body",
            ),
        )
    }

    @Test
    fun fallbackReplyMessage_usesTargetIdsAndPreviewBody() {
        val payload = ReplyQuoteSelectionReply(
            targetMessageId = "missing-parent",
            targetSenderId = "alice",
            selectedText = "part",
            previewBody = "full body",
        )
        val message = ReplyQuoteInteraction.fallbackReplyMessage(payload, chatId = "chat-1")
        assertEquals("missing-parent", message.id)
        assertEquals("chat-1", message.chat_id)
        assertEquals("alice", message.sender_id)
        assertEquals("full body", message.body)
    }

    @Test
    fun findTargetMessage_byIdOrPendingId() {
        val parent = Message(
            id = "server-1",
            chat_id = "c",
            sender_id = "alice",
            body = "original",
            pending_id = "pend-1",
        )
        val map = mapOf(parent.id to parent)
        assertEquals(parent, ReplyQuoteInteraction.findTargetMessage(map, "server-1"))
        assertEquals(parent, ReplyQuoteInteraction.findTargetMessage(map, "pend-1"))
        assertNull(ReplyQuoteInteraction.findTargetMessage(map, "other"))
    }

    @Test
    fun focusIndex_matchesIdOrPendingId() {
        val messages = listOf(
            Message(id = "a", chat_id = "c", sender_id = "u", body = "1"),
            Message(id = "server-b", chat_id = "c", sender_id = "u", body = "2", pending_id = "pend-b"),
            Message(id = "c", chat_id = "c", sender_id = "u", body = "3"),
        )
        assertEquals(0, ReplyQuoteInteraction.focusIndexIn(messages, "a"))
        assertEquals(1, ReplyQuoteInteraction.focusIndexIn(messages, "server-b"))
        assertEquals(1, ReplyQuoteInteraction.focusIndexIn(messages, "pend-b"))
        assertEquals(-1, ReplyQuoteInteraction.focusIndexIn(messages, "missing"))
    }

    @Test
    fun matchesFocusTarget_andHighlightDuration() {
        val msg = Message(id = "server-b", chat_id = "c", sender_id = "u", body = "2", pending_id = "pend-b")
        assertTrue(ReplyQuoteInteraction.matchesFocusTarget(msg, "server-b"))
        assertTrue(ReplyQuoteInteraction.matchesFocusTarget(msg, "pend-b"))
        assertFalse(ReplyQuoteInteraction.matchesFocusTarget(msg, "other"))
        assertTrue(ReplyQuoteInteraction.FOCUS_HIGHLIGHT_MS > 0L)
    }
}
