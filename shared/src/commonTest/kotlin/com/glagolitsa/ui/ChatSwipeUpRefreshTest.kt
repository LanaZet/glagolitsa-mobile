// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatSwipeUpRefreshTest {
    @Test
    fun shouldTriggerSwipeUpRefresh_requiresThreshold() {
        assertFalse(shouldTriggerSwipeUpRefresh(0f))
        assertFalse(shouldTriggerSwipeUpRefresh(SWIPE_UP_REFRESH_THRESHOLD_PX - 1f))
        assertTrue(shouldTriggerSwipeUpRefresh(SWIPE_UP_REFRESH_THRESHOLD_PX))
        assertTrue(shouldTriggerSwipeUpRefresh(SWIPE_UP_REFRESH_THRESHOLD_PX + 40f))
    }
}
