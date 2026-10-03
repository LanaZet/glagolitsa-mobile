// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components.nav

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.glagolitsa.ui.components.GlassPillFrame

private val NavBarShape = RoundedCornerShape(percent = 50)

@Composable
fun GlassNavBarFrame(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassPillFrame(
        modifier = modifier,
        shape = NavBarShape,
        content = content,
    )
}