// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import com.glagolitsa.currentTimeMillis
import com.glagolitsa.formatLocalClockFromMillis
import com.glagolitsa.formatLocalDayKeyFromMillis
import com.glagolitsa.formatLocalDayLabelFromMillis
import com.glagolitsa.parseIsoTimestampMillis

/**
 * Форматирует ISO-8601 в подпись под сообщением: "14:35" или "28.06.2026".
 */
fun formatMessageTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val value = iso.trim()

    if (hasExplicitTimeZone(value)) {
        parseIsoTimestampMillis(value)
            ?.let { return formatLocalClockFromMillis(it) }
    }

    val timeSegment = value.substringAfter('T', "")
        .substringBefore('Z')
        .substringBefore('+')
        .trim()

    val clock = timeSegment.take(5).takeIf { it.length == 5 && it[2] == ':' }
    if (clock != null) return clock

    val datePart = value.substringBefore('T').trim()
    return if (datePart.length >= 10) {
        val y = datePart.substring(0, 4)
        val m = datePart.substring(5, 7)
        val d = datePart.substring(8, 10)
        "$d.$m.$y"
    } else {
        ""
    }
}

/**
 * Local calendar day key for a message (`yyyy-MM-dd`), or null if unparseable.
 */
fun messageLocalDayKey(iso: String?): String? {
    val millis = parseIsoTimestampMillis(iso?.trim().orEmpty()) ?: return null
    return formatLocalDayKeyFromMillis(millis)
}

/**
 * Day chip label for the chat timeline: «Сегодня» / «Вчера» / date.
 */
fun formatChatDayLabel(iso: String?, nowMillis: Long = currentTimeMillis()): String {
    val millis = parseIsoTimestampMillis(iso?.trim().orEmpty()) ?: return ""
    return formatLocalDayLabelFromMillis(millis, nowMillis)
}

private fun hasExplicitTimeZone(value: String): Boolean {
    if ('T' !in value) return false
    val timeStart = value.indexOf('T') + 1
    return value.endsWith('Z') ||
        value.indexOf('+', startIndex = timeStart) >= 0 ||
        value.indexOf('-', startIndex = timeStart) >= 0
}
