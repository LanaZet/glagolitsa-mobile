// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatListSwipeDeletePolicyTest {
    @Test
    fun thresholdUsesFractionUntilMaxCap() {
        assertEquals(0f, ChatListSwipeDeletePolicy.positionalThresholdPx(0f, maxPx = 56f))
        assertEquals(44f, ChatListSwipeDeletePolicy.positionalThresholdPx(200f, maxPx = 56f))
        assertEquals(56f, ChatListSwipeDeletePolicy.positionalThresholdPx(400f, maxPx = 56f))
        assertEquals(0f, ChatListSwipeDeletePolicy.positionalThresholdPx(200f, maxPx = 0f))
    }
}
