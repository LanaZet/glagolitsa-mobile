// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.push

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushWakeResultTest {
    @Test
    fun empty_shouldNotNotify() {
        assertFalse(PushWakeResult.Empty.shouldShowLocalNotification)
    }

    @Test
    fun applied_withFlag_shouldNotify() {
        val r = PushWakeResult(appliedInbound = 2, shouldShowLocalNotification = true)
        assertTrue(r.shouldShowLocalNotification)
        assertTrue(r.appliedInbound >= 1)
    }
}
