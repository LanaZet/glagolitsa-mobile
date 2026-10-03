// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.model.Message
import com.glagolitsa.parseIsoTimestampMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatTimelineTest {
    private fun message(id: String, createdAt: String) = Message(
        id = id,
        chat_id = "c1",
        sender_id = "u1",
        body = id,
        created_at = createdAt,
    )

    @Test
    fun buildChatTimeline_insertsDayHeadersBetweenLocalDays() {
        val messages = listOf(
            message("m1", "2026-07-01T10:00:00Z"),
            message("m2", "2026-07-01T18:00:00Z"),
            message("m3", "2026-07-02T09:00:00Z"),
        )
        val timeline = buildChatTimeline(messages, nowMillis = parseIsoTimestampMillis("2026-07-03T12:00:00Z")!!)
        val kinds = timeline.map {
            when (it) {
                is ChatTimelineItem.DayHeader -> "day:${it.label}"
                is ChatTimelineItem.Bubble -> "msg:${it.message.id}"
            }
        }
        assertEquals(5, timeline.size)
        assertTrue(kinds[0].startsWith("day:"))
        assertEquals("msg:m1", kinds[1])
        assertEquals("msg:m2", kinds[2])
        assertTrue(kinds[3].startsWith("day:"))
        assertEquals("msg:m3", kinds[4])
        // Same local day for m1/m2 → only one header before them.
        assertEquals(2, timeline.count { it is ChatTimelineItem.DayHeader })
    }

    @Test
    fun shouldAutoScroll_onAppendButNotOnPrepend() {
        val base = listOf(message("m2", "2026-07-02T10:00:00Z"))
        val appended = base + message("m3", "2026-07-02T11:00:00Z")
        val prepended = listOf(message("m1", "2026-07-01T10:00:00Z")) + base

        assertTrue(
            shouldAutoScrollToLatest(
                previousCount = 1,
                previousLastId = "m2",
                messages = appended,
            ),
        )
        assertFalse(
            shouldAutoScrollToLatest(
                previousCount = 1,
                previousLastId = "m2",
                messages = prepended,
            ),
        )
    }

    @Test
    fun stickyDate_hiddenWhenInListHeaderIsAtTop() {
        val timeline = buildChatTimeline(
            listOf(
                message("y1", "2026-07-01T10:00:00Z"),
                message("y2", "2026-07-01T18:00:00Z"),
                message("t1", "2026-07-02T09:00:00Z"),
            ),
            nowMillis = parseIsoTimestampMillis("2026-07-02T12:00:00Z")!!,
        )
        val visual = visualTimelineItems(timeline, loadingOlder = false)
        // newest-first: [t1, day-today, y2, y1, day-yesterday]
        val yesterdayHeader = visual.indexOfLast {
            val row = (it as? ChatTimelineVisualItem.Row)?.item
            row is ChatTimelineItem.DayHeader
        }
        val yesterdayBubble = yesterdayHeader - 1
        val yesterdayLabel = (
            (visual[yesterdayHeader] as ChatTimelineVisualItem.Row).item as ChatTimelineItem.DayHeader
        ).label
        assertEquals(
            null,
            stickyDateLabelForVisibleIndices(
                visual,
                visibleIndices = listOf(yesterdayBubble, yesterdayHeader),
                reverseLayout = true,
            ),
        )
        assertEquals(
            yesterdayLabel,
            stickyDateLabelForVisibleIndices(
                visual,
                visibleIndices = listOf(yesterdayBubble),
                reverseLayout = true,
            ),
        )
    }

    @Test
    fun buildChatTimeline_usesUniqueKeysForInvalidTimestamps() {
        val timeline = buildChatTimeline(
            listOf(
                message("m1", "bad"),
                message("m2", "2026-07-02T11:00:00Z"),
                message("m3", "also-bad"),
            ),
            nowMillis = parseIsoTimestampMillis("2026-07-03T12:00:00Z")!!,
        )

        val keys = timeline.map { it.listKey }
        assertEquals(keys.distinct(), keys)
    }
}
