// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import kotlin.test.Test
import kotlin.test.assertEquals

class AvatarCropMathTest {
    @Test
    fun selectionCentersWideImageIntoSquareFrame() {
        val selection = avatarCropSelection(
            bitmapWidth = 400,
            bitmapHeight = 300,
            frameSizePx = 100f,
            zoom = 1f,
            offsetXPx = 0f,
            offsetYPx = 0f,
        )

        assertEquals(50, selection.left)
        assertEquals(0, selection.top)
        assertEquals(300, selection.size)
    }

    @Test
    fun selectionMovesWhenImageIsDragged() {
        val selection = avatarCropSelection(
            bitmapWidth = 400,
            bitmapHeight = 300,
            frameSizePx = 100f,
            zoom = 1f,
            offsetXPx = 10f,
            offsetYPx = 0f,
        )

        assertEquals(20, selection.left)
        assertEquals(0, selection.top)
        assertEquals(300, selection.size)
    }
}
