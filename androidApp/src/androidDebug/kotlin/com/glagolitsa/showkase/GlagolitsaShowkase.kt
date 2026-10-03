// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.showkase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airbnb.android.showkase.annotation.ShowkaseComposable
import com.glagolitsa.ui.components.AuthFormFields
import com.glagolitsa.ui.components.GlagolitsaButton
import com.glagolitsa.ui.components.GlagolitsaButtonSize
import com.glagolitsa.ui.components.GlagolitsaButtonStyle
import com.glagolitsa.ui.components.GlagolitsaInput
import com.glagolitsa.ui.components.GlagolitsaInputSize
import com.glagolitsa.ui.components.GlagolitsaInputVariant
import com.glagolitsa.ui.components.SlavicPillButton
import com.glagolitsa.ui.components.SlavicPillOutlinedButton
import com.glagolitsa.ui.components.SlavicPillTextButton
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaSpacing
import com.glagolitsa.ui.theme.GlagolitsaTheme

private val ShowkasePreviewBg = Modifier
    .background(GlagolitsaColors.AuthScreenGradient)
    .padding(GlagolitsaSpacing.lg)

// ── Inputs / States (DS 1.0) ───────────────────────────────────────────────

@ShowkaseComposable(name = "Default", group = "Inputs / States")
@Composable
fun ShowkaseInputDefault() {
    GlagolitsaTheme {
        GlagolitsaInput(
            value = "",
            onValueChange = {},
            placeholder = "Placeholder",
            modifier = ShowkasePreviewBg.fillMaxWidth(),
        )
    }
}

@ShowkaseComposable(name = "Filled", group = "Inputs / States")
@Composable
fun ShowkaseInputFilled() {
    GlagolitsaTheme {
        GlagolitsaInput(
            value = "Текст введен",
            onValueChange = {},
            placeholder = "Placeholder",
            modifier = ShowkasePreviewBg.fillMaxWidth(),
        )
    }
}

@ShowkaseComposable(name = "Error", group = "Inputs / States")
@Composable
fun ShowkaseInputError() {
    GlagolitsaTheme {
        GlagolitsaInput(
            value = "bad",
            onValueChange = {},
            placeholder = "Placeholder",
            modifier = ShowkasePreviewBg.fillMaxWidth(),
            errorMessage = "Текст ошибки",
        )
    }
}

@ShowkaseComposable(name = "Success", group = "Inputs / States")
@Composable
fun ShowkaseInputSuccess() {
    GlagolitsaTheme {
        GlagolitsaInput(
            value = "ok",
            onValueChange = {},
            placeholder = "Placeholder",
            modifier = ShowkasePreviewBg.fillMaxWidth(),
            successMessage = "Успешно",
        )
    }
}

@ShowkaseComposable(name = "Disabled", group = "Inputs / States")
@Composable
fun ShowkaseInputDisabled() {
    GlagolitsaTheme {
        GlagolitsaInput(
            value = "",
            onValueChange = {},
            placeholder = "Placeholder",
            modifier = ShowkasePreviewBg.fillMaxWidth(),
            enabled = false,
        )
    }
}

@ShowkaseComposable(name = "Read Only", group = "Inputs / States")
@Composable
fun ShowkaseInputReadOnly() {
    GlagolitsaTheme {
        GlagolitsaInput(
            value = "Текст только для чтения",
            onValueChange = {},
            placeholder = "Placeholder",
            modifier = ShowkasePreviewBg.fillMaxWidth(),
            readOnly = true,
        )
    }
}

// ── Inputs / Variants ────────────────────────────────────────────────────────

@ShowkaseComposable(name = "Username", group = "Inputs / Variants")
@Composable
fun ShowkaseInputUsername() {
    GlagolitsaTheme {
        GlagolitsaInput(
            value = "frictiya",
            onValueChange = {},
            placeholder = "Логин",
            modifier = ShowkasePreviewBg.fillMaxWidth(),
            variant = GlagolitsaInputVariant.Username,
        )
    }
}

@ShowkaseComposable(name = "Password", group = "Inputs / Variants")
@Composable
fun ShowkaseInputPassword() {
    GlagolitsaTheme {
        GlagolitsaInput(
            value = "secret123",
            onValueChange = {},
            placeholder = "Пароль",
            modifier = ShowkasePreviewBg.fillMaxWidth(),
            variant = GlagolitsaInputVariant.Password,
        )
    }
}

@ShowkaseComposable(name = "Email", group = "Inputs / Variants")
@Composable
fun ShowkaseInputEmail() {
    GlagolitsaTheme {
        GlagolitsaInput(
            value = "example@mail.ru",
            onValueChange = {},
            placeholder = "Email",
            modifier = ShowkasePreviewBg.fillMaxWidth(),
            variant = GlagolitsaInputVariant.Email,
        )
    }
}

@ShowkaseComposable(name = "Search", group = "Inputs / Variants")
@Composable
fun ShowkaseInputSearch() {
    GlagolitsaTheme {
        GlagolitsaInput(
            value = "",
            onValueChange = {},
            placeholder = "Поиск",
            modifier = ShowkasePreviewBg.fillMaxWidth(),
            variant = GlagolitsaInputVariant.Search,
        )
    }
}

@ShowkaseComposable(name = "Phone", group = "Inputs / Variants")
@Composable
fun ShowkaseInputPhone() {
    GlagolitsaTheme {
        GlagolitsaInput(
            value = "+7 999 123-45-67",
            onValueChange = {},
            placeholder = "Телефон",
            modifier = ShowkasePreviewBg.fillMaxWidth(),
            variant = GlagolitsaInputVariant.Phone,
        )
    }
}

@ShowkaseComposable(name = "Multiline", group = "Inputs / Variants")
@Composable
fun ShowkaseInputMultiline() {
    GlagolitsaTheme {
        GlagolitsaInput(
            value = "",
            onValueChange = {},
            placeholder = "Многострочный текст...",
            modifier = ShowkasePreviewBg.fillMaxWidth(),
            variant = GlagolitsaInputVariant.Multiline,
            singleLine = false,
        )
    }
}

@ShowkaseComposable(name = "Sizes", group = "Inputs / Variants")
@Composable
fun ShowkaseInputSizes() {
    GlagolitsaTheme {
        Column(
            modifier = ShowkasePreviewBg.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.md),
        ) {
            GlagolitsaInput(
                value = "Small 40",
                onValueChange = {},
                placeholder = "Small",
                size = GlagolitsaInputSize.Small,
            )
            GlagolitsaInput(
                value = "Default 48",
                onValueChange = {},
                placeholder = "Default",
                size = GlagolitsaInputSize.Default,
            )
            GlagolitsaInput(
                value = "Large 56",
                onValueChange = {},
                placeholder = "Large",
                size = GlagolitsaInputSize.Large,
            )
        }
    }
}

// ── Auth form (как на экране входа) ──────────────────────────────────────────

@ShowkaseComposable(name = "Login fields", group = "Auth")
@Composable
fun ShowkaseAuthLoginFields() {
    GlagolitsaTheme {
        Column(
            modifier = ShowkasePreviewBg.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.lg),
        ) {
            Text("Вход", color = GlagolitsaColors.TextSecondary)
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
            SlavicPillButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text("Войти")
            }
        }
    }
}

// ── Buttons ──────────────────────────────────────────────────────────────────

@ShowkaseComposable(name = "Primary", group = "Buttons")
@Composable
fun ShowkaseButtonPrimary() {
    GlagolitsaTheme {
        GlagolitsaButton(
            onClick = {},
            style = GlagolitsaButtonStyle.Primary,
            size = GlagolitsaButtonSize.Large,
            modifier = ShowkasePreviewBg,
        ) {
            Text("Создать аккаунт")
        }
    }
}

@ShowkaseComposable(name = "Danger", group = "Buttons")
@Composable
fun ShowkaseButtonDanger() {
    GlagolitsaTheme {
        GlagolitsaButton(
            onClick = {},
            style = GlagolitsaButtonStyle.Danger,
            size = GlagolitsaButtonSize.Large,
            modifier = ShowkasePreviewBg,
        ) {
            Text("Выйти из аккаунта")
        }
    }
}

@ShowkaseComposable(name = "Pill outlined", group = "Buttons")
@Composable
fun ShowkaseButtonPillOutlined() {
    GlagolitsaTheme {
        SlavicPillOutlinedButton(onClick = {}, modifier = ShowkasePreviewBg) {
            Text("Войти как Marco")
        }
    }
}

@ShowkaseComposable(name = "Pill text", group = "Buttons")
@Composable
fun ShowkaseButtonPillText() {
    GlagolitsaTheme {
        SlavicPillTextButton(onClick = {}, modifier = ShowkasePreviewBg) {
            Text("Уже есть аккаунт? Войти")
        }
    }
}
