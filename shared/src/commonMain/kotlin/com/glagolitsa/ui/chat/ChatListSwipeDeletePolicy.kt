// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

internal object ChatListSwipeDeletePolicy {
    const val THRESHOLD_FRACTION = 0.22f
    const val THRESHOLD_MAX_DP = 56f

    fun positionalThresholdPx(totalDistancePx: Float, maxPx: Float): Float {
        if (totalDistancePx <= 0f || maxPx <= 0f) return 0f
        return (totalDistancePx * THRESHOLD_FRACTION).coerceAtMost(maxPx)
    }
}
