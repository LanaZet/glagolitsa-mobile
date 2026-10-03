// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StableDeviceIdTest {
    @Test
    fun stableDeviceId_isDeterministicPerAccountAndSeed() {
        val a = stableDeviceId("user-1", "seed-phone")
        val b = stableDeviceId("user-1", "seed-phone")
        assertEquals(a, b)
        assertTrue(a.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun stableDeviceId_differsByAccountOrSeed() {
        val base = stableDeviceId("user-1", "seed-a")
        assertNotEquals(base, stableDeviceId("user-2", "seed-a"))
        assertNotEquals(base, stableDeviceId("user-1", "seed-b"))
    }
}
