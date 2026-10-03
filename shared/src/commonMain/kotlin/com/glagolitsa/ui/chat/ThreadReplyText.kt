// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

internal fun threadReplyCountLabel(count: Int): String =
    "$count ${threadReplyWord(count)}"

internal fun threadReplyWord(count: Int): String {
    val mod100 = count % 100
    if (mod100 in 11..14) return "ответов"
    return when (count % 10) {
        1 -> "ответ"
        in 2..4 -> "ответа"
        else -> "ответов"
    }
}
