// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.navigation.MainTab
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaSpacing

private val BottomNavMaxWidth = 430.dp
private val BottomNavMinHeight = 52.dp
private val BottomNavShape = RoundedCornerShape(28.dp)

val VisibleMainTabs = listOf(
    MainTab.Chats,
    MainTab.Calls,
    MainTab.Settings,
)

/**
 * Нижняя навигация Design System 1.0:
 * Основные разделы приложения — компактная плавающая капсула с орнаментной палитрой.
 */
@Composable
fun BottomNavBar(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    unreadChatsCount: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = GlagolitsaSpacing.lg,
                vertical = GlagolitsaSpacing.sm,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = BottomNavMaxWidth)
                .heightIn(min = BottomNavMinHeight)
                .background(GlagolitsaColors.SurfaceFloating, BottomNavShape)
                .border(1.dp, GlagolitsaColors.BorderMuted, BottomNavShape)
                .padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VisibleMainTabs.forEachIndexed { index, tab ->
                NavBarTabItem(
                    tab = tab,
                    selected = selectedTab == tab,
                    unreadCount = if (tab == MainTab.Chats) unreadChatsCount else 0,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier.weight(1f),
                )
                if (index != VisibleMainTabs.lastIndex) {
                    BottomNavDivider()
                }
            }
        }
    }
}

@Composable
private fun BottomNavDivider() {
    Box(
        modifier = Modifier
            .height(24.dp)
            .width(1.dp)
            .background(GlagolitsaColors.DividerSubtle),
    )
}
