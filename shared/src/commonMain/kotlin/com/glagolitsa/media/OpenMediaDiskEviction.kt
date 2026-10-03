// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

/**
 * Disk budget for open-media cache (Telegram keeps a bounded local media store).
 * Applied after writes; pure policy so unit-testable without IO.
 */
object OpenMediaDiskBudget {
    const val MAX_TOTAL_BYTES: Long = 200L * 1024L * 1024L // 200 MiB
    const val MAX_FILES: Int = 400

    /**
     * Given file sizes sorted oldest-first by last access, return paths/ids to delete
     * until under budget. [entries] = (id, sizeBytes, lastAccessMs) oldest first.
     */
    fun idsToEvict(
        entries: List<Triple<String, Long, Long>>,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
        maxFiles: Int = MAX_FILES,
    ): List<String> {
        if (entries.isEmpty()) return emptyList()
        var total = entries.sumOf { it.second.coerceAtLeast(0L) }
        var count = entries.size
        val toDelete = mutableListOf<String>()
        // entries sorted oldest access first
        for ((id, size, _) in entries) {
            if (total <= maxTotalBytes && count <= maxFiles) break
            toDelete += id
            total -= size.coerceAtLeast(0L)
            count -= 1
        }
        return toDelete
    }
}
