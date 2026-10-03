// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.Chat
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.security.LocalAuthenticator
import com.glagolitsa.ui.components.nav.BottomNavBar
import com.glagolitsa.ui.components.nav.VisibleMainTabs
import com.glagolitsa.ui.components.rememberNavigationBarInset
import com.glagolitsa.ui.components.systemNavigationBarSafeArea
import com.glagolitsa.ui.components.topSafeArea
import com.glagolitsa.ui.navigation.MainTab
import com.glagolitsa.ui.theme.GlagolitsaColors

/** Высота плавающей нижней навигации + отступ для прокручиваемого контента. */
private val BottomNavReservedHeight = 72.dp

/**
 * Корневой экран после входа: вкладки Чаты / Контакты / Звонки / Настройки.
 */
@Composable
fun MainScreen(
    repository: MessengerRepository,
    localAuthenticator: LocalAuthenticator,
    initialTab: MainTab = MainTab.Chats,
    updatePrompt: ClientUpdatePromptState? = null,
    onChatSelected: (Chat) -> Unit,
    onCreateChannel: () -> Unit = {},
    onLoggedOut: () -> Unit,
) {
    val initialVisibleTab = if (initialTab in VisibleMainTabs) initialTab else MainTab.Chats
    var selectedTab by remember(initialVisibleTab) { mutableStateOf(initialVisibleTab) }
    val unreadCounts by repository.unreadCounts.collectAsState()
    val unreadChatsCount = unreadCounts.count { it.value > 0 }
    val bottomContentPadding = BottomNavReservedHeight + rememberNavigationBarInset()

    AppBackHandler(enabled = selectedTab != MainTab.Chats) {
        selectedTab = MainTab.Chats
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GlagolitsaColors.ScreenGradient),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .topSafeArea(),
        ) {
            // In-flow: pushes the tab content down instead of covering chat chrome.
            if (updatePrompt != null) {
                ClientUpdateSoftBannerHost(
                    prompt = updatePrompt,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    MainTab.Chats -> ChatListScreen(
                        repository = repository,
                        onChatSelected = onChatSelected,
                        onCreateChannel = onCreateChannel,
                        bottomContentPadding = bottomContentPadding,
                    )

                    MainTab.Contacts -> PlaceholderTab(
                        title = "Контакты",
                        bottomContentPadding = bottomContentPadding,
                    )

                    MainTab.Calls -> CallsScreen(
                        repository = repository,
                        bottomContentPadding = bottomContentPadding,
                    )

                    MainTab.Settings -> ProfileScreen(
                        repository = repository,
                        localAuthenticator = localAuthenticator,
                        onLoggedOut = onLoggedOut,
                        onChatSelected = onChatSelected,
                        bottomContentPadding = bottomContentPadding,
                    )
                }
            }
        }

        BottomNavBar(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            unreadChatsCount = unreadChatsCount,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemNavigationBarSafeArea(),
        )
    }
}

@Composable
private fun PlaceholderTab(
    title: String,
    bottomContentPadding: Dp,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomContentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = GlagolitsaColors.TextMuted,
        )
    }
}
