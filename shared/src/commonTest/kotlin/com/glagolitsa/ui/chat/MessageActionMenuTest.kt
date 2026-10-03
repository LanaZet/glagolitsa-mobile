// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageActionMenuTest {
    @Test
    fun actionListFitsStandardSevenItemMenuOnNormalViewport() {
        assertEquals(
            372.dp,
            messageActionListMaxHeight(actionCount = 7, viewportHeight = 746.dp),
        )
    }

    @Test
    fun actionListUsesCompactScrollableHeightOnShortViewport() {
        assertEquals(
            240.dp,
            messageActionListMaxHeight(actionCount = 7, viewportHeight = 360.dp),
        )
    }
}
