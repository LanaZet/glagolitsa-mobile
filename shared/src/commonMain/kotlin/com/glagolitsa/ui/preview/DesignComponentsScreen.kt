// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.glagolitsa.ui.components.AuthFormFields
import com.glagolitsa.ui.components.AppEmptyState
import com.glagolitsa.ui.components.AppTopBar
import com.glagolitsa.ui.components.GlagolitsaButton
import com.glagolitsa.ui.components.GlagolitsaButtonSize
import com.glagolitsa.ui.components.GlagolitsaButtonStyle
import com.glagolitsa.ui.components.GlagolitsaInput
import com.glagolitsa.ui.components.GlagolitsaInputSize
import com.glagolitsa.ui.components.GlagolitsaInputVariant
import com.glagolitsa.ui.components.SlavicPillButton
import com.glagolitsa.ui.components.SlavicPillOutlinedButton
import com.glagolitsa.ui.components.SlavicPillTextButton
import com.glagolitsa.ui.components.nav.BottomNavBar
import com.glagolitsa.ui.navigation.MainTab
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaSpacing
import com.glagolitsa.ui.theme.GlagolitsaTheme

private val previewBg = Modifier
    .fillMaxSize()
    .background(GlagolitsaColors.AuthScreenGradient)

/** Каталог UI-компонентов Design System 1.0 (общий для desktop и @Preview). */
@Composable
fun DesignComponentsScreen(modifier: Modifier = Modifier) {
    GlagolitsaTheme {
        Column(
            modifier = modifier
                .then(previewBg)
                .verticalScroll(rememberScrollState())
                .padding(GlagolitsaSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.lg),
        ) {
            Text("Navigation", style = MaterialTheme.typography.titleMedium, color = GlagolitsaColors.TextSecondary)
            AppTopBar(title = "Чаты", subtitle = "3 непрочитанных")
            BottomNavBar(
                selectedTab = MainTab.Chats,
                onTabSelected = {},
                unreadChatsCount = 3,
            )

            Text("Inputs", style = MaterialTheme.typography.titleMedium, color = GlagolitsaColors.TextSecondary)
            GlagolitsaInput(value = "", onValueChange = {}, placeholder = "Placeholder", modifier = Modifier.fillMaxWidth())
            GlagolitsaInput(
                value = "Текст введен",
                onValueChange = {},
                placeholder = "Placeholder",
                modifier = Modifier.fillMaxWidth(),
            )
            GlagolitsaInput(
                value = "bad",
                onValueChange = {},
                placeholder = "Placeholder",
                modifier = Modifier.fillMaxWidth(),
                errorMessage = "Текст ошибки",
            )
            GlagolitsaInput(
                value = "ok",
                onValueChange = {},
                placeholder = "Placeholder",
                modifier = Modifier.fillMaxWidth(),
                successMessage = "Всё верно",
            )
            GlagolitsaInput(
                value = "",
                onValueChange = {},
                placeholder = "Поиск",
                modifier = Modifier.fillMaxWidth(),
                variant = GlagolitsaInputVariant.Search,
            )
            GlagolitsaInput(
                value = "secret",
                onValueChange = {},
                placeholder = "Пароль",
                modifier = Modifier.fillMaxWidth(),
                variant = GlagolitsaInputVariant.Password,
            )

            Text("Sizes", style = MaterialTheme.typography.titleMedium, color = GlagolitsaColors.TextSecondary)
            GlagolitsaInput(value = "Small 40", onValueChange = {}, placeholder = "Small", size = GlagolitsaInputSize.Small)
            GlagolitsaInput(value = "Default 48", onValueChange = {}, placeholder = "Default", size = GlagolitsaInputSize.Default)
            GlagolitsaInput(value = "Large 56", onValueChange = {}, placeholder = "Large", size = GlagolitsaInputSize.Large)

            Text("Buttons", style = MaterialTheme.typography.titleMedium, color = GlagolitsaColors.TextSecondary)
            GlagolitsaButton(
                onClick = {},
                style = GlagolitsaButtonStyle.Primary,
                size = GlagolitsaButtonSize.Large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Primary")
            }
            GlagolitsaButton(
                onClick = {},
                style = GlagolitsaButtonStyle.Danger,
                size = GlagolitsaButtonSize.Large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Danger")
            }
            SlavicPillButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text("Pill primary")
            }
            SlavicPillOutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text("Pill outlined")
            }
            SlavicPillTextButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text("Pill text")
            }

            Text("Auth fields", style = MaterialTheme.typography.titleMedium, color = GlagolitsaColors.TextSecondary)
            AuthFormFields {
                GlagolitsaInput(
                    value = "frictiya",
                    onValueChange = {},
                    placeholder = "Логин",
                    variant = GlagolitsaInputVariant.Username,
                )
                GlagolitsaInput(
                    value = "",
                    onValueChange = {},
                    placeholder = "Пароль",
                    variant = GlagolitsaInputVariant.Password,
                )
            }

            Text("Empty states", style = MaterialTheme.typography.titleMedium, color = GlagolitsaColors.TextSecondary)
            AppEmptyState(
                title = "Пока нет чатов",
                message = "Личные диалоги и группы появятся здесь после синхронизации.",
            )
            AppEmptyState(
                title = "Нет непрочитанных",
                message = "Все сообщения прочитаны.",
            )
        }
    }
}
