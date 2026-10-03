// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * Отступы под системные панели Android (статус-бар, навигация, клавиатура).
 *
 * Важно: на экране чата нельзя вешать [screenSafeArea] на весь Column —
 * при открытии IME весь контент уезжает под «челку». Используйте связку
 * [screenTopSafeArea] + [chatComposerInsets].
 */

@Composable
fun Modifier.topSafeArea(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))

@Composable
fun Modifier.systemNavigationBarSafeArea(): Modifier =
    windowInsetsPadding(WindowInsets.navigationBars)

/** Только статус-бар. Для списка чатов, профиля, логина. */
@Composable
fun Modifier.screenTopSafeArea(): Modifier = topSafeArea()

/** Низ экрана чата: клавиатура + жестовая навигация. */
@Composable
fun Modifier.chatComposerInsets(): Modifier =
    windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))
        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))

/** Все safe area сразу — только там, где нет поля ввода с IME. */
@Composable
fun Modifier.screenSafeArea(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing)

/** Высота нижней системной панели для padding у bottom nav. */
@Composable
fun rememberNavigationBarInset(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()