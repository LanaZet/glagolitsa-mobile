// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaSpacing

/** Общая обёртка экранов входа и регистрации — фон и сетка отступов DS 1.0 INPUTS. */
@Composable
fun AuthFormScreen(
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val columnModifier = Modifier
        .fillMaxSize()
        .screenTopSafeArea()
        .background(GlagolitsaColors.AuthScreenGradient)
        .padding(horizontal = GlagolitsaSpacing.xxl)
        .padding(top = GlagolitsaSpacing.xxl, bottom = GlagolitsaSpacing.lg)
        .then(
            if (scrollable) {
                Modifier.verticalScroll(rememberScrollState())
            } else {
                Modifier
            },
        )

    Column(
        modifier = modifier.then(columnModifier),
        verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.lg),
        content = content,
    )
}

/** Группа полей формы — 12dp между связанными инпутами. */
@Composable
fun AuthFormFields(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.md),
        content = content,
    )
}