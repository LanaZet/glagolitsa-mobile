// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

/**
 * Collapse long chat bubbles (Telegram-style “read more”), pure rules.
 * Quote preview uses its own fixed scroll viewport and does not use this.
 */
object MessageBodyCollapsePolicy {

    /** Visible lines when the bubble body is collapsed. */
    const val COLLAPSED_MAX_LINES = 8

    /** Length above which the body is considered collapsible. */
    const val COLLAPSE_CHAR_THRESHOLD = 320

    fun isCollapsible(body: String): Boolean {
        if (body.isBlank()) return false
        if (body.length >= COLLAPSE_CHAR_THRESHOLD) return true
        return body.count { it == '\n' } >= COLLAPSED_MAX_LINES
    }
}
