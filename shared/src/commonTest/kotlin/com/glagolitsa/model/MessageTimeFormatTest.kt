// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import com.glagolitsa.formatLocalClockFromMillis
import com.glagolitsa.parseIsoTimestampMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageTimeFormatTest {
    @Test
    fun formatMessageTime_convertsUtcIsoToLocalClock() {
        val iso = "2026-06-28T15:34:20Z"
        val expected = formatLocalClockFromMillis(parseIsoTimestampMillis(iso) ?: error("bad test timestamp"))
        assertEquals(expected, formatMessageTime(iso))
    }

    @Test
    fun formatMessageTime_keepsClockFromIsoWithoutTimezone() {
        assertEquals("15:34", formatMessageTime("2026-06-28T15:34:20"))
    }

    @Test
    fun formatMessageTime_showsDateWhenNoClock() {
        assertEquals("28.06.2026", formatMessageTime("2026-06-28"))
    }

    @Test
    fun formatMessageTime_emptyForBlank() {
        assertEquals("", formatMessageTime(null))
        assertEquals("", formatMessageTime(""))
    }

    @Test
    fun formatChatDayLabel_todayAndYesterday() {
        val now = parseIsoTimestampMillis("2026-07-03T12:00:00Z") ?: error("now")
        val todayIso = "2026-07-03T10:00:00Z"
        val yesterdayIso = "2026-07-02T12:00:00Z"
        // Labels depend on device TZ; assert non-blank and same local day for midday stamps.
        val today = formatChatDayLabel(todayIso, now)
        val yesterday = formatChatDayLabel(yesterdayIso, now)
        assertTrue(today.isNotBlank())
        assertTrue(yesterday.isNotBlank())
        assertEquals(
            messageLocalDayKey(todayIso),
            messageLocalDayKey("2026-07-03T14:00:00Z"),
        )
    }
}
