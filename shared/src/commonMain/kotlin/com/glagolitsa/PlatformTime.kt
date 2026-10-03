// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa

/** Epoch millis UTC. */
expect fun currentTimeMillis(): Long

/** ISO-8601 UTC для сортировки и отображения сообщений. */
expect fun currentIsoTimestamp(): String

/** Парсит ISO-8601 в epoch millis для хронологической сортировки. */
expect fun parseIsoTimestampMillis(iso: String): Long?

/** Форматирует epoch millis в ISO-8601 UTC. */
expect fun formatIsoFromMillis(millis: Long): String

/** Форматирует epoch millis в локальное время устройства, HH:mm. */
expect fun formatLocalClockFromMillis(millis: Long): String

/**
 * Local calendar day key `yyyy-MM-dd` for grouping chat messages by day.
 */
expect fun formatLocalDayKeyFromMillis(millis: Long): String

/**
 * Human day label for chat day chips: «Сегодня», «Вчера», or a date.
 * [nowMillis] is the reference "today" (device clock).
 */
expect fun formatLocalDayLabelFromMillis(millis: Long, nowMillis: Long = currentTimeMillis()): String
