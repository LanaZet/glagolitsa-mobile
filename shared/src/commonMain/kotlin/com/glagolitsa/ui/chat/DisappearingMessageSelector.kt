// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.DisappearingMessageTtl
import com.glagolitsa.ui.theme.GlagolitsaColors

@Composable
fun DisappearingMessageSelector(
    selected: DisappearingMessageTtl,
    onSelected: (DisappearingMessageTtl) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DisappearingMessageTtl.entries.forEach { option ->
            val active = option == selected
            Surface(
                modifier = Modifier.clickable { onSelected(option) },
                shape = RoundedCornerShape(percent = 50),
                color = if (active) {
                    GlagolitsaColors.AccentRed.copy(alpha = 0.22f)
                } else {
                    GlagolitsaColors.Surface800.copy(alpha = 0.7f)
                },
            ) {
                Text(
                    text = option.label,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) GlagolitsaColors.TextPrimary else GlagolitsaColors.TextSecondary,
                )
            }
        }
    }
}