// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallMediaKeyManagerTest {
    @Test
    fun eligibility_joinedOnly() {
        val m = CallMediaKeyManager()
        assertTrue(m.isEligibleTarget("joined", left = false))
        assertTrue(m.isEligibleTarget("accepted", left = false))
        assertFalse(m.isEligibleTarget("ringing", left = false))
        assertFalse(m.isEligibleTarget("joined", left = true))
    }

    @Test
    fun generateKey_length32() {
        val m = CallMediaKeyManager()
        val key = m.generateKey { size -> ByteArray(size) { 7 } }
        assertEquals(32, key.size)
        assertEquals(7, m.activeKeyOrNull()?.get(0))
        m.clear()
        assertEquals(null, m.activeKeyOrNull())
    }
}
