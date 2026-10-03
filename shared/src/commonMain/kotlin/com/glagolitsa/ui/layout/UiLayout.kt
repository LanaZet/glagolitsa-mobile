// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.layout

import androidx.compose.ui.unit.dp

/**
 * Общие константы вёрстки под разные экраны (телефон / планшет).
 */
object UiLayout {
    const val CHAT_HORIZONTAL_PADDING = 16
    const val CHAT_TOP_PADDING = 8
}

/** Размеры чатов: список, шапка, composer, пузыри — под телефон (~360–430 dp). */
object ChatLayout {
    // Список чатов
    val listHorizontalPadding = 16.dp
    val listVerticalPadding = 8.dp
    val listSectionSpacing = 12.dp
    val listItemSpacing = 8.dp
    val listFilterSpacing = 10.dp
    val listAvatarSize = 48.dp
    val listCardMinHeight = 68.dp
    val listCardPaddingH = 12.dp
    val listCardPaddingV = 10.dp
    val listCardRadius = 16.dp
    val listFilterHeight = 32.dp
    val listFilterPaddingH = 18.dp
    val listFilterRadius = 16.dp

    // Шапка переписки
    val topBarAvatarSize = 40.dp
    val topBarActionSize = 32.dp
    val topBarIconSize = 18.dp

    // Composer
    val composerHeight = 48.dp

    // Пузыри
    val bubbleCheckIconSize = 14.dp
}
