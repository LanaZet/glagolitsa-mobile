// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatMessageTextTest {
    @Test
    fun messageSelectionRangeNormalizesDragDirection() {
        assertEquals(2 until 5, messageSelectionRange(start = 5, end = 2, textLength = 10))
        assertEquals(2 until 5, messageSelectionRange(start = 2, end = 5, textLength = 10))
    }

    @Test
    fun messageSelectionRangeRejectsEmptyOrInvalidSelection() {
        assertNull(messageSelectionRange(start = null, end = 3, textLength = 10))
        assertNull(messageSelectionRange(start = 3, end = 3, textLength = 10))
        assertNull(messageSelectionRange(start = 0, end = 4, textLength = 0))
    }

    @Test
    fun messageSelectionRangeExtractsSelectedQuoteSubstring() {
        val body = "Use the edit icon to pin, add or delete clips."
        val range = messageSelectionRange(start = 0, end = 17, textLength = body.length)
        assertEquals(0 until 17, range)
        assertEquals("Use the edit icon", body.substring(range!!.first, range.last + 1))
    }

    @Test
    fun selectionMenuAnchorY_fallsBackWhenLayoutMissing() {
        assertEquals(0, selectionMenuAnchorY(layout = null, range = 0 until 5))
        assertEquals(0, selectionMenuAnchorY(layout = null, range = null))
    }

    @Test
    fun isAttachmentPlaceholderBodyOnlyMatchesSingleLineLegacyAttachmentLabels() {
        assertTrue(isAttachmentPlaceholderBody("📎 image.png"))
        assertTrue(isAttachmentPlaceholderBody("  📎 image.png (123 байт)  "))
        assertFalse(isAttachmentPlaceholderBody("📎 image.png\ncaption"))
        assertFalse(isAttachmentPlaceholderBody("caption 📎 image.png"))
    }
}
