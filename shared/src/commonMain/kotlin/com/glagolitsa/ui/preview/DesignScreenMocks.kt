// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.components.AuthFormFields
import com.glagolitsa.ui.components.AuthFormScreen
import com.glagolitsa.ui.components.GlagolitsaInput
import com.glagolitsa.ui.components.GlagolitsaInputSize
import com.glagolitsa.ui.components.GlagolitsaInputVariant
import com.glagolitsa.ui.components.SlavicPillButton
import com.glagolitsa.ui.components.SlavicPillTextButton

import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaSpacing
import com.glagolitsa.ui.theme.GlagolitsaTheme

@Composable
fun DesignLoginMock(modifier: Modifier = Modifier) {
    GlagolitsaTheme {
        AuthFormScreen(modifier = modifier) {
            Column(verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.sm)) {
                Text("Glagolitsa", style = MaterialTheme.typography.headlineLarge)
                Text("Вход", style = MaterialTheme.typography.titleMedium, color = GlagolitsaColors.TextSecondary)
                Text(
                    text = "Логин из регистрации (@username), не имя в профиле.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.TextTertiary,
                )
            }

            AuthFormFields {
                GlagolitsaInput(
                    value = "",
                    onValueChange = {},
                    placeholder = "Логин",
                    modifier = Modifier.fillMaxWidth(),
                    size = GlagolitsaInputSize.Default,
                    variant = GlagolitsaInputVariant.Username,
                )
                GlagolitsaInput(
                    value = "•••••••",
                    onValueChange = {},
                    placeholder = "Пароль",
                    modifier = Modifier.fillMaxWidth(),
                    size = GlagolitsaInputSize.Default,
                    variant = GlagolitsaInputVariant.Password,
                )
            }

            SlavicPillButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text("Войти")
            }

            SlavicPillTextButton(
                onClick = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = GlagolitsaSpacing.xs),
            ) {
                Text("Нет аккаунта? Зарегистрироваться")
            }
        }
    }
}

@Composable
fun DesignRegisterMock(modifier: Modifier = Modifier) {
    GlagolitsaTheme {
        AuthFormScreen(modifier = modifier, scrollable = true) {
            Column(verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.sm)) {
                Text("Glagolitsa", style = MaterialTheme.typography.headlineLarge)
                Text("Регистрация", style = MaterialTheme.typography.titleMedium, color = GlagolitsaColors.TextSecondary)
            }

            AuthFormFields {
                GlagolitsaInput(
                    value = "frictiya",
                    onValueChange = {},
                    placeholder = "Логин",
                    modifier = Modifier.fillMaxWidth(),
                    variant = GlagolitsaInputVariant.Username,
                )
                GlagolitsaInput(
                    value = "user@example.com",
                    onValueChange = {},
                    placeholder = "Email (необязательно)",
                    modifier = Modifier.fillMaxWidth(),
                    variant = GlagolitsaInputVariant.Email,
                )
                GlagolitsaInput(
                    value = "",
                    onValueChange = {},
                    placeholder = "Пароль",
                    modifier = Modifier.fillMaxWidth(),
                    variant = GlagolitsaInputVariant.Password,
                )
                GlagolitsaInput(
                    value = "",
                    onValueChange = {},
                    placeholder = "Повторите пароль",
                    modifier = Modifier.fillMaxWidth(),
                    variant = GlagolitsaInputVariant.Password,
                )
            }

            SlavicPillButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text("Создать аккаунт")
            }

            SlavicPillTextButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text("Уже есть аккаунт? Войти")
            }
        }
    }
}

@Composable
fun DesignChatListMock(modifier: Modifier = Modifier) {
    GlagolitsaTheme {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(GlagolitsaColors.Background950)
                .padding(horizontal = GlagolitsaSpacing.lg, vertical = GlagolitsaSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.md),
        ) {
            Text(
                text = "Чаты",
                style = MaterialTheme.typography.headlineLarge,
                color = GlagolitsaColors.TextPrimary,
            )
            GlagolitsaInput(
                value = "",
                onValueChange = {},
                placeholder = "Поиск",
                modifier = Modifier.fillMaxWidth(),
                size = GlagolitsaInputSize.Small,
                variant = GlagolitsaInputVariant.Search,
            )
            DesignChatListRow(title = "Анна", preview = "Добро пожаловать в Glagolitsa!", time = "12:40", unread = 2)
            DesignChatListRow(title = "Команда дизайна", preview = "Макеты готовы к ревью", time = "вчера", unread = 0)
            DesignChatListRow(title = "bob", preview = "Ок, договорились", time = "пн", unread = 0, online = true)
        }
    }
}

@Composable
private fun DesignChatListRow(
    title: String,
    preview: String,
    time: String,
    unread: Int,
    online: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GlagolitsaColors.Surface800.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = GlagolitsaColors.TextPrimary)
            Text(time, style = MaterialTheme.typography.labelSmall, color = GlagolitsaColors.TextTertiary)
        }
        Text(
            text = buildString {
                if (online) append("● ")
                append(preview)
                if (unread > 0) append("  [$unread]")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = GlagolitsaColors.TextSecondary,
        )
    }
}