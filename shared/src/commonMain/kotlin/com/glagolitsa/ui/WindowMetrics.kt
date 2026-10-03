// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp

@Composable
fun rememberScreenWidthDp(): Dp {
    val density = LocalDensity.current
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    return remember(containerWidthPx, density) {
        with(density) { containerWidthPx.toDp() }
    }
}

@Composable
fun rememberScreenWidthDpValue(): Int {
    val width = rememberScreenWidthDp()
    return remember(width) { width.value.toInt() }
}