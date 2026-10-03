// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenMediaDiskBudgetTest {
    @Test
    fun evictsOldestUntilUnderByteBudget() {
        val entries = listOf(
            Triple("a", 80L, 1L),
            Triple("b", 80L, 2L),
            Triple("c", 80L, 3L),
        )
        val del = OpenMediaDiskBudget.idsToEvict(entries, maxTotalBytes = 100, maxFiles = 100)
        assertEquals(listOf("a", "b"), del)
    }

    @Test
    fun evictsByFileCount() {
        val entries = (1..5).map { i -> Triple("f$i", 1L, i.toLong()) }
        val del = OpenMediaDiskBudget.idsToEvict(entries, maxTotalBytes = 1_000, maxFiles = 2)
        assertEquals(listOf("f1", "f2", "f3"), del)
        assertTrue(entries.size - del.size <= 2)
    }

    @Test
    fun noEvictionWhenUnderBudget() {
        val entries = listOf(Triple("a", 10L, 1L), Triple("b", 10L, 2L))
        assertTrue(OpenMediaDiskBudget.idsToEvict(entries, maxTotalBytes = 100, maxFiles = 10).isEmpty())
    }
}
