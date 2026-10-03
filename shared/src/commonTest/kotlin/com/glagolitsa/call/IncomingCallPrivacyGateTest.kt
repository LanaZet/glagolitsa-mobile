// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncomingCallPrivacyGateTest {
    @Test
    fun blocked() {
        val d = IncomingCallPrivacyGate.decide(true, true, true)
        assertEquals(IncomingCallPrivacyGate.RingMode.BLOCKED, d.mode)
    }

    @Test
    fun acceptedContactFullScreen() {
        val d = IncomingCallPrivacyGate.decide(false, true, true)
        assertEquals(IncomingCallPrivacyGate.RingMode.FULL_SCREEN, d.mode)
        assertTrue(d.highPriority)
    }

    @Test
    fun unknownRequestOnly() {
        val d = IncomingCallPrivacyGate.decide(false, false, true)
        assertEquals(IncomingCallPrivacyGate.RingMode.REQUEST_ONLY, d.mode)
        assertFalse(d.highPriority)
    }

    @Test
    fun pushAllowlistNoPII() {
        assertFalse(IncomingCallPrivacyGate.pushAllowlist.contains("caller_name"))
        assertTrue(IncomingCallPrivacyGate.pushAllowlist.contains("call_id"))
    }
}
