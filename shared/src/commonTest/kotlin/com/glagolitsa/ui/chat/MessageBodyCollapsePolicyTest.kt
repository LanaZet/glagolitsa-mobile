// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageBodyCollapsePolicyTest {

    @Test
    fun shortBody_notCollapsible() {
        assertFalse(MessageBodyCollapsePolicy.isCollapsible("hello"))
        assertFalse(MessageBodyCollapsePolicy.isCollapsible(""))
    }

    @Test
    fun longBody_isCollapsible() {
        val long = "word ".repeat(80).trim()
        assertTrue(MessageBodyCollapsePolicy.isCollapsible(long))
    }

    @Test
    fun manyNewlines_isCollapsible() {
        // Need at least COLLAPSED_MAX_LINES newline chars (see isCollapsible).
        val lines = (0..MessageBodyCollapsePolicy.COLLAPSED_MAX_LINES)
            .joinToString("\n") { "line $it" }
        assertTrue(MessageBodyCollapsePolicy.isCollapsible(lines))
    }
}
