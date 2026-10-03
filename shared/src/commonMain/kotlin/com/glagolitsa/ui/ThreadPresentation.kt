// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import com.glagolitsa.model.Message

internal const val THREAD_INITIAL_VISIBLE_REPLIES = 4

internal data class ThreadReplyWindow(
    val visibleMessages: List<Message>,
    val hiddenCount: Int,
)

internal fun buildThreadReplyWindow(
    messages: List<Message>,
    showAll: Boolean,
    initialVisibleCount: Int = THREAD_INITIAL_VISIBLE_REPLIES,
): ThreadReplyWindow {
    // Storage keeps chat messages chronological; the thread screen presents newest replies first.
    val newestFirstMessages = messages.asReversed()
    val visibleMessages = if (showAll || newestFirstMessages.size <= initialVisibleCount) {
        newestFirstMessages
    } else {
        newestFirstMessages.take(initialVisibleCount)
    }
    return ThreadReplyWindow(
        visibleMessages = visibleMessages,
        hiddenCount = (newestFirstMessages.size - visibleMessages.size).coerceAtLeast(0),
    )
}
