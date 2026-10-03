// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.navigation

import com.glagolitsa.model.Chat
import com.glagolitsa.model.Message

internal sealed interface AppRoute {
    data class Login(val prefilledUsername: String? = null) : AppRoute
    data object Register : AppRoute
    data class Main(val tab: MainTab = MainTab.Chats) : AppRoute
    /** Apple-style modal form for public/private channel creation. */
    data object CreateChannel : AppRoute
    data class ChatDetail(
        val chat: Chat,
        val focusMessageId: String? = null,
    ) : AppRoute
    data class ChatThread(val chat: Chat, val parentMessage: Message) : AppRoute
    data class ChatInfo(val chat: Chat) : AppRoute
    /** Editable chat settings (appearance, privacy links) — opened from ⋮ in the chat top bar. */
    data class ChatSettings(val chat: Chat) : AppRoute
    data class SafetyNumber(val chat: Chat, val partnerUserId: String) : AppRoute
}