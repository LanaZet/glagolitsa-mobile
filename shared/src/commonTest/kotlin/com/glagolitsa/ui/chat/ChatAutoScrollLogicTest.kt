// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatAutoScrollLogicTest {
    private fun message(id: String) = Message(
        id = id,
        chat_id = "chat-1",
        sender_id = "user-1",
        body = "hi",
        created_at = "2026-07-13T10:00:00Z",
    )

    @Test
    fun shouldAutoScroll_whenMessageAppendedAtEnd() {
        val messages = listOf(message("m1"), message("m2"))
        assertTrue(
            shouldAutoScrollToLatest(
                previousCount = 1,
                previousLastId = "m1",
                messages = messages,
            ),
        )
    }

    @Test
    fun shouldNotAutoScroll_whenOlderMessagesPrepended() {
        val messages = listOf(message("m0"), message("m1"))
        assertFalse(
            shouldAutoScrollToLatest(
                previousCount = 1,
                previousLastId = "m1",
                messages = messages,
            ),
        )
    }

    @Test
    fun shouldNotAutoScroll_whenCountUnchanged() {
        val messages = listOf(message("m1"))
        assertFalse(
            shouldAutoScrollToLatest(
                previousCount = 1,
                previousLastId = "m1",
                messages = messages,
            ),
        )
    }

    @Test
    fun shouldNotAutoScroll_onEmptyList() {
        assertFalse(
            shouldAutoScrollToLatest(
                previousCount = 0,
                previousLastId = null,
                messages = emptyList(),
            ),
        )
    }

    @Test
    fun autoScrollTargetIndex_pointsToLastMessage() {
        val messages = listOf(message("m1"), message("m2"))
        assertEquals(1, autoScrollTargetIndex(messages))
    }

    @Test
    fun autoScrollTargetIndex_nullForEmptyList() {
        assertNull(autoScrollTargetIndex(emptyList()))
    }

    @Test
    fun shouldNotAutoScroll_whenReadingHistoryAndIncomingArrives() {
        val messages = listOf(message("m1"), message("m2"))
        assertFalse(
            shouldAutoScrollToLatest(
                previousCount = 1,
                previousLastId = "m1",
                messages = messages,
                pinnedToBottom = false,
                lastMessageIsOwn = false,
            ),
        )
    }

    @Test
    fun shouldAutoScroll_ownOutgoingEvenWhenReadingHistory() {
        val messages = listOf(message("m1"), message("m2"))
        assertTrue(
            shouldAutoScrollToLatest(
                previousCount = 1,
                previousLastId = "m1",
                messages = messages,
                pinnedToBottom = false,
                lastMessageIsOwn = true,
            ),
        )
    }

    @Test
    fun shouldNotAutoScroll_whenPrependKeepsLastId() {
        val messages = listOf(message("old"), message("m1"), message("m2"))
        assertFalse(
            shouldAutoScrollToLatest(
                previousCount = 2,
                previousLastId = "m2",
                messages = messages,
                pinnedToBottom = true,
            ),
        )
    }

    @Test
    fun visualTimeline_newestFirstAndLoadingAtEnd() {
        val timeline = buildChatTimeline(listOf(message("m1"), message("m2")))
        val visual = visualTimelineItems(timeline, loadingOlder = true)
        val firstBubble = (visual.first() as ChatTimelineVisualItem.Row).item as ChatTimelineItem.Bubble
        assertEquals("m2", firstBubble.message.id)
        assertEquals(ChatTimelineVisualItem.LoadingOlder, visual.last())
        assertEquals(0, visualIndexForMessageId(visual, "m2"))
        assertEquals(0, visualNewestIndex(visual))
    }

    @Test
    fun pinnedToLatest_reverseUsesIndexZero() {
        assertTrue(isPinnedToLatest(reverseLayout = true, firstVisibleIndex = 0, firstVisibleOffset = 12, canScrollForward = true))
        assertFalse(isPinnedToLatest(reverseLayout = true, firstVisibleIndex = 8, firstVisibleOffset = 0, canScrollForward = true))
        assertTrue(isPinnedToLatest(reverseLayout = false, firstVisibleIndex = 20, firstVisibleOffset = 0, canScrollForward = false))
    }

    @Test
    fun animateScroll_disabledDuringFling() {
        assertFalse(shouldAnimateScrollToItem(isScrollInProgress = true))
        assertTrue(shouldAnimateScrollToItem(isScrollInProgress = false))
    }

    @Test
    fun prefetchOlder_reverseNearEnd() {
        assertTrue(shouldPrefetchOlder(reverseLayout = true, firstVisibleIndex = 18, totalItems = 20))
        assertFalse(shouldPrefetchOlder(reverseLayout = true, firstVisibleIndex = 0, totalItems = 20))
        assertTrue(shouldPrefetchOlder(reverseLayout = false, firstVisibleIndex = 1, totalItems = 20))
    }

    @Test
    fun visibleWindow_keepsTailAndExpandsFromOldest() {
        val messages = (1..100).map { message("m$it") }
        assertEquals(80, visibleMessageWindow(messages, oldestKeptId = null).size)
        assertEquals("m21", visibleMessageWindow(messages, oldestKeptId = null).first().id)
        val expanded = visibleMessageWindow(messages, oldestKeptId = "m10")
        assertEquals("m10", expanded.first().id)
        assertEquals(91, expanded.size)
    }

    @Test
    fun neighborIds_includeRadius() {
        val messages = listOf(message("a"), message("b"), message("c"), message("d"))
        assertEquals(setOf("a", "b", "c"), neighborMessageIds(messages, setOf("b"), radius = 1))
    }

    @Test
    fun reservedMediaHeight_usesMetadata() {
        val height = reservedAttachmentHeightDp(pixelWidth = 200, pixelHeight = 200, maxWidthDp = 200f)
        assertEquals(200f, height)
        assertEquals(188f, reservedAttachmentHeightDp(null, null, 200f))
    }

    @Test
    fun timelineContentType_mapsKinds() {
        val bubble = ChatTimelineItem.Bubble(message("m1"), 0)
        assertEquals("text", timelineContentType(bubble, null))
        assertEquals("photo", timelineContentType(bubble, com.glagolitsa.media.AttachmentKind.IMAGE))
        assertEquals(
            "day",
            timelineContentType(ChatTimelineItem.DayHeader("2026-07-13", "Сегодня")),
        )
    }

    @Test
    fun visibleTimelineMessageIds_skipHeadersAndLoader() {
        assertEquals(
            setOf("m1", "m2"),
            visibleTimelineMessageIds(listOf("m1", "day-2026-07-13", "loading-older", "m2")),
        )
    }
}