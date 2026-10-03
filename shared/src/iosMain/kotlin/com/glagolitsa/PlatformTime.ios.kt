// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

actual fun currentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun currentIsoTimestamp(): String = formatIsoFromMillis(currentTimeMillis())

actual fun parseIsoTimestampMillis(iso: String): Long? {
    val formatter = NSISO8601DateFormatter()
    val date = formatter.dateFromString(iso.trim()) ?: return null
    return (date.timeIntervalSince1970 * 1000.0).toLong()
}

actual fun formatIsoFromMillis(millis: Long): String {
    val formatter = NSISO8601DateFormatter()
    val date = NSDate.dateWithTimeIntervalSince1970(millis / 1000.0)
    return formatter.stringFromDate(date)
}

actual fun formatLocalClockFromMillis(millis: Long): String {
    // NSDateFormatter uses device locale/timezone by default (no explicit props — K/N bindings vary).
    val formatter = NSDateFormatter()
    formatter.dateFormat = "HH:mm"
    val date = NSDate.dateWithTimeIntervalSince1970(millis / 1000.0)
    return formatter.stringFromDate(date)
}

actual fun formatLocalDayKeyFromMillis(millis: Long): String {
    val formatter = NSDateFormatter()
    formatter.dateFormat = "yyyy-MM-dd"
    val date = NSDate.dateWithTimeIntervalSince1970(millis / 1000.0)
    return formatter.stringFromDate(date)
}

actual fun formatLocalDayLabelFromMillis(millis: Long, nowMillis: Long): String {
    val calendar = NSCalendar.currentCalendar
    val day = NSDate.dateWithTimeIntervalSince1970(millis / 1000.0)
    val today = NSDate.dateWithTimeIntervalSince1970(nowMillis / 1000.0)
    val startDay = calendar.startOfDayForDate(day)
    val startToday = calendar.startOfDayForDate(today)
    val components = calendar.components(NSCalendarUnitDay, fromDate = startDay, toDate = startToday, options = 0u)
    return when (components.day) {
        0L -> "Сегодня"
        1L -> "Вчера"
        else -> {
            val formatter = NSDateFormatter()
            formatter.locale = NSLocale(localeIdentifier = "ru_RU")
            val yearDay = calendar.component(NSCalendarUnitYear, fromDate = day)
            val yearToday = calendar.component(NSCalendarUnitYear, fromDate = today)
            formatter.dateFormat = if (yearDay == yearToday) "d MMMM" else "d MMMM yyyy"
            formatter.stringFromDate(day)
        }
    }
}
