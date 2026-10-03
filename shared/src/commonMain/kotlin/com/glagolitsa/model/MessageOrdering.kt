// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import com.glagolitsa.parseIsoTimestampMillis

/**
 * Стабильная хронологическая сортировка ленты по дате/времени.
 * Строковое сравнение ISO ломается при смешении `…Z` и `….123Z` — нормализуем sort key.
 */
fun List<Message>.sortedForChat(): List<Message> = sortedWith(
    compareBy<Message> { messageSortKey(it.created_at) }
        .thenBy { it.id },
)

internal fun messageSortKey(createdAt: String?): String = when {
    createdAt.isNullOrBlank() -> "9"
    else -> normalizedUtcIsoSortKey(createdAt)
        ?: parseIsoTimestampMillis(createdAt)?.let { "1${it.toString().padStart(20, '0')}" }
        ?: "8${createdAt.trim()}"
}

private fun normalizedUtcIsoSortKey(createdAt: String): String? {
    val value = createdAt.trim()
    if (!value.endsWith("Z")) return null
    val body = value.dropLast(1)
    val seconds = body.substringBefore('.')
    if (seconds.length != "yyyy-MM-ddTHH:mm:ss".length) return null
    val fraction = body.substringAfter('.', missingDelimiterValue = "")
    if (fraction.any { !it.isDigit() }) return null
    return "0$seconds.${fraction.take(9).padEnd(9, '0')}Z"
}
