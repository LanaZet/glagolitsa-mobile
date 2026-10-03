// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaSpacing

@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigation: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    // When both leading and trailing actions are present (e.g. «Отмена» · title · «Создать»),
    // pin the title to true horizontal center so uneven button widths don't shift it.
    val centerTitle = navigation != null && actions != null

    if (centerTitle) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
        ) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
                content = navigation!!,
            )
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 88.dp)
                    .widthIn(min = 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                AppTopBarTitle(title = title, textAlign = TextAlign.Center)
                AppTopBarSubtitle(subtitle = subtitle, textAlign = TextAlign.Center)
            }
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                content = actions!!,
            )
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.sm),
        ) {
            if (navigation != null) {
                navigation()
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 0.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                AppTopBarTitle(title = title, textAlign = TextAlign.Start)
                AppTopBarSubtitle(subtitle = subtitle, textAlign = TextAlign.Start)
            }
            if (actions != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

@Composable
private fun AppTopBarTitle(
    title: String,
    textAlign: TextAlign,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        color = GlagolitsaColors.TextPrimary,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AppTopBarSubtitle(
    subtitle: String?,
    textAlign: TextAlign,
) {
    if (subtitle.isNullOrBlank()) return
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = GlagolitsaColors.TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun AppTopBarTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fontWeight: FontWeight = FontWeight.Medium,
    color: Color = GlagolitsaColors.AccentRedText,
    disabledColor: Color = GlagolitsaColors.TextTertiary,
) {
    // Center label inside the 44dp hit target so it shares a baseline with the title
    // (plain Text + heightIn alone top-aligns and looks shifted next to headlineSmall).
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = GlagolitsaSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) color else disabledColor,
            fontWeight = fontWeight,
            maxLines = 1,
        )
    }
}

@Composable
fun AppEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = GlagolitsaSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.sm),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier.padding(bottom = GlagolitsaSpacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = GlagolitsaColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (!message.isNullOrBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextTertiary,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Box(modifier = Modifier.padding(top = GlagolitsaSpacing.sm)) {
                action()
            }
        }
    }
}
