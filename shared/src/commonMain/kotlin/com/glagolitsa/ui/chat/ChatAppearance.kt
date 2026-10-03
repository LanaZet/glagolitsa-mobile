// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.ui.theme.GlagolitsaColors

private const val CHAT_DARKNESS_DEFAULT = 0.42f
private const val CHAT_BUTTON_COLOR_DEFAULT = "red"
private const val CHAT_DARKNESS_KEY_PREFIX = "chat.appearance.darkness."
private const val CHAT_BUTTON_COLOR_KEY_PREFIX = "chat.appearance.buttonColor."
private const val CHAT_TEXT_SCALE_KEY_PREFIX = "chat.appearance.textScale."

data class ChatAppearanceSettings(
    val darkness: Float = CHAT_DARKNESS_DEFAULT,
    val buttonColor: ChatButtonColor = ChatButtonColor.Red,
)

enum class ChatButtonColor(
    val storageKey: String,
    val label: String,
) {
    Red("red", "Красные"),
    Gold("gold", "Золотые"),
    Blue("blue", "Синие"),
    Green("green", "Зелёные");

    fun colorFor(darkness: Float): Color {
        val base = when (this) {
            Red -> GlagolitsaColors.AccentRed
            Gold -> GlagolitsaColors.OrnamentGold
            Blue -> GlagolitsaColors.StatusInfo
            Green -> GlagolitsaColors.StatusSuccess
        }
        val lift = ((darkness - 0.62f) * 0.22f).coerceIn(0f, 0.14f)
        return lerp(base, Color.White, lift)
    }

    fun contentColor(): Color =
        if (this == Gold) GlagolitsaColors.TextOnGold else Color.White

    companion object {
        fun fromStorageKey(value: String): ChatButtonColor =
            entries.firstOrNull { it.storageKey == value } ?: Red
    }
}

fun chatAppearanceBackground(darkness: Float): Brush {
    val d = darkness.coerceIn(0f, 1f)
    return Brush.verticalGradient(
        colors = listOf(
            lerp(GlagolitsaColors.ChatWallpaperTop, GlagolitsaColors.ChatWallpaperTopDeep, d),
            lerp(GlagolitsaColors.ChatWallpaperMid, GlagolitsaColors.ChatWallpaperMidDeep, d),
            lerp(GlagolitsaColors.ChatWallpaperBottom, GlagolitsaColors.ChatWallpaperBottomDeep, d),
        ),
    )
}

fun chatAppearancePanelColor(darkness: Float): Color {
    val d = darkness.coerceIn(0f, 1f)
    return lerp(GlagolitsaColors.Surface700, GlagolitsaColors.Surface800, d).copy(alpha = 0.74f)
}

suspend fun MessengerRepository.loadChatAppearanceSettings(chatId: String): ChatAppearanceSettings {
    val darkness = loadProfileSetting(CHAT_DARKNESS_KEY_PREFIX + chatId, CHAT_DARKNESS_DEFAULT.toString())
        .toFloatOrNull()
        ?.coerceIn(0f, 1f)
        ?: CHAT_DARKNESS_DEFAULT
    val buttonColor = ChatButtonColor.fromStorageKey(
        loadProfileSetting(CHAT_BUTTON_COLOR_KEY_PREFIX + chatId, CHAT_BUTTON_COLOR_DEFAULT),
    )
    return ChatAppearanceSettings(
        darkness = darkness,
        buttonColor = buttonColor,
    )
}

suspend fun MessengerRepository.saveChatAppearanceSettings(
    chatId: String,
    settings: ChatAppearanceSettings,
) {
    saveProfileSetting(CHAT_DARKNESS_KEY_PREFIX + chatId, settings.darkness.coerceIn(0f, 1f).toString())
    saveProfileSetting(CHAT_BUTTON_COLOR_KEY_PREFIX + chatId, settings.buttonColor.storageKey)
}

suspend fun MessengerRepository.loadChatTextScaleSetting(chatId: String): Float =
    ChatSettingsLogic.parseTextScale(
        loadProfileSetting(
            CHAT_TEXT_SCALE_KEY_PREFIX + chatId,
            ChatSettingsLogic.TEXT_SCALE_DEFAULT.toString(),
        ),
    )

suspend fun MessengerRepository.saveChatTextScaleSetting(chatId: String, textScale: Float) {
    saveProfileSetting(
        CHAT_TEXT_SCALE_KEY_PREFIX + chatId,
        ChatSettingsLogic.coerceTextScale(textScale).toString(),
    )
}
