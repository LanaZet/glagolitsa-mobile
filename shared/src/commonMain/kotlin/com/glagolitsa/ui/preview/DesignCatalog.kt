// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaSpacing

enum class DesignScreen(val label: String) {
    Login("Вход"),
    Register("Регистрация"),
    ChatList("Чаты"),
    Components("Компоненты"),
}

/** Каталог экранов для итерации дизайна на desktop (hot reload). */
@Composable
fun DesignCatalog(modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf(DesignScreen.Login) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GlagolitsaColors.Background950),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(GlagolitsaColors.Surface800)
                .padding(horizontal = GlagolitsaSpacing.lg, vertical = GlagolitsaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.sm),
        ) {
            Text(
                text = "Glagolitsa Design",
                style = MaterialTheme.typography.titleLarge,
                color = GlagolitsaColors.TextPrimary,
            )
            Text(
                text = "Сохраните файл в shared/ — UI обновится автоматически (hot reload)",
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.TextTertiary,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.sm),
            ) {
                DesignScreen.entries.forEach { screen ->
                    DesignScreenChip(
                        label = screen.label,
                        selected = screen == selected,
                        onClick = { selected = screen },
                    )
                }
            }
        }

        DesignPhoneFrame(modifier = Modifier.weight(1f)) {
            when (selected) {
                DesignScreen.Login -> DesignLoginMock(Modifier.fillMaxSize())
                DesignScreen.Register -> DesignRegisterMock(Modifier.fillMaxSize())
                DesignScreen.ChatList -> DesignChatListMock(Modifier.fillMaxSize())
                DesignScreen.Components -> DesignComponentsScreen(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun DesignScreenChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) GlagolitsaColors.AccentRed else GlagolitsaColors.Surface700
    val textColor = if (selected) GlagolitsaColors.TextPrimary else GlagolitsaColors.TextSecondary

    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = textColor,
    )
}