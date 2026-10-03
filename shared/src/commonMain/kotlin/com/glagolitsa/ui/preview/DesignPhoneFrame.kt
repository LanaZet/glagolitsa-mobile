// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Рамка телефона для дизайн-превью на desktop. */
@Composable
fun DesignPhoneFrame(
    modifier: Modifier = Modifier,
    width: Dp = 390.dp,
    height: Dp = 844.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .shadow(24.dp, RoundedCornerShape(28.dp))
                .size(width, height)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.Black),
        ) {
            content()
        }
    }
}