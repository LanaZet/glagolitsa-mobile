// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.preview

import androidx.compose.runtime.Composable
import com.glagolitsa.ui.components.AppEmptyState
import com.glagolitsa.ui.components.nav.BottomNavBar
import com.glagolitsa.ui.navigation.MainTab
import com.glagolitsa.ui.theme.GlagolitsaTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

@Preview
@Composable
private fun PreviewLoginScreen() {
    DesignLoginMock()
}

@Preview
@Composable
private fun PreviewRegisterScreen() {
    DesignRegisterMock()
}

@Preview
@Composable
private fun PreviewChatListScreen() {
    DesignChatListMock()
}

@Preview
@Composable
private fun PreviewComponentsScreen() {
    GlagolitsaTheme {
        DesignComponentsScreen()
    }
}

@Preview
@Composable
private fun PreviewBottomNavigation() {
    GlagolitsaTheme {
        BottomNavBar(
            selectedTab = MainTab.Chats,
            onTabSelected = {},
            unreadChatsCount = 7,
        )
    }
}

@Preview
@Composable
private fun PreviewEmptyState() {
    GlagolitsaTheme {
        AppEmptyState(
            title = "Каналов пока нет",
            message = "Когда появятся каналы, они будут доступны в этом фильтре.",
        )
    }
}
