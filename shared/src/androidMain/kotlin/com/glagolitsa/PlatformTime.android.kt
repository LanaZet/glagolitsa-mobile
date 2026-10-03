// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun currentIsoTimestamp(): String =
    Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

actual fun parseIsoTimestampMillis(iso: String): Long? = runCatching {
    Instant.parse(iso.trim()).toEpochMilli()
}.getOrNull()

actual fun formatIsoFromMillis(millis: Long): String =
    Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.MILLIS).toString()

actual fun formatLocalClockFromMillis(millis: Long): String =
    DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))

actual fun formatLocalDayKeyFromMillis(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    return day.toString() // yyyy-MM-dd
}

actual fun formatLocalDayLabelFromMillis(millis: Long, nowMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return when (ChronoUnit.DAYS.between(day, today)) {
        0L -> "Сегодня"
        1L -> "Вчера"
        else -> {
            val locale = Locale.forLanguageTag("ru")
            val pattern = if (day.year == today.year) "d MMMM" else "d MMMM yyyy"
            day.format(DateTimeFormatter.ofPattern(pattern, locale))
        }
    }
}
