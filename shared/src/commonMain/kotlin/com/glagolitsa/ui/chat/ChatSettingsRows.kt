// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.components.GlagolitsaSwitch
import com.glagolitsa.ui.theme.GlagolitsaColors

@Composable
internal fun SafetyCodeActionRow(
    onHelpClick: () -> Unit,
    onOpenClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Код безопасности",
                color = GlagolitsaColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            SafetyCodeHelpBadge(onClick = onHelpClick)
        }
        Text(
            text = "Открыть",
            color = GlagolitsaColors.TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenClick)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun PreferenceSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = enabled) { onCheckedChange(!checked) },
            color = GlagolitsaColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        GlagolitsaSwitch(
            checked = checked,
            enabled = enabled,
            compactTouchTarget = true,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SafetyCodeHelpBadge(
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .padding(2.dp)
            .clip(CircleShape)
            .border(1.dp, GlagolitsaColors.TextTertiary.copy(alpha = 0.85f), CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "Что такое код безопасности"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "?",
            style = MaterialTheme.typography.labelSmall,
            color = GlagolitsaColors.TextTertiary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
