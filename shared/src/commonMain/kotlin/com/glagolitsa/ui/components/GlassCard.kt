// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaShapes
import com.glagolitsa.ui.theme.GlagolitsaSpacing

@Composable
fun GlassCard(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = GlagolitsaShapes.md
    val colors = CardDefaults.cardColors(
        containerColor = GlagolitsaColors.Surface800.copy(alpha = 0.72f),
        contentColor = GlagolitsaColors.TextPrimary,
    )

    val cardModifier = if (onClick == null) {
        modifier.fillMaxWidth()
    } else {
        modifier.fillMaxWidth().clickable(onClick = onClick)
    }

    Card(
        modifier = cardModifier,
        colors = colors,
        shape = shape,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = GlagolitsaSpacing.md,
                vertical = GlagolitsaSpacing.md,
            ),
            content = content,
        )
    }
}