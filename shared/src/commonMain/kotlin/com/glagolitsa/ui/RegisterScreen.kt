// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.glagolitsa.auth.RegisterProgress
import com.glagolitsa.auth.RegistrationValidation
import com.glagolitsa.auth.RegistrationValidationException
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.ui.api.registrationUiError
import com.glagolitsa.ui.components.AuthFormFields
import com.glagolitsa.ui.components.AuthFormScreen
import com.glagolitsa.ui.components.AuthInlineTextButton
import com.glagolitsa.ui.components.AuthRaisedPillButton
import com.glagolitsa.ui.components.GlagolitsaInput
import com.glagolitsa.ui.components.GlagolitsaInputSize
import com.glagolitsa.ui.components.GlagolitsaInputVariant
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaSpacing
import kotlinx.coroutines.launch

@Composable
fun RegisterScreen(
    repository: MessengerRepository,
    onRegistered: () -> Unit,
    onOpenLogin: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var fieldErrors by remember { mutableStateOf<Map<RegistrationValidation.Field, String>>(emptyMap()) }
    var serverFieldErrors by remember { mutableStateOf<Map<RegistrationValidation.Field, String>>(emptyMap()) }
    var formBanner by remember { mutableStateOf<String?>(null) }
    var suggestLogin by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<RegisterProgress?>(null) }

    fun clearServerFeedback() {
        serverFieldErrors = emptyMap()
        formBanner = null
        suggestLogin = false
    }

    fun clearFieldError(field: RegistrationValidation.Field) {
        if (fieldErrors.containsKey(field)) {
            fieldErrors = fieldErrors - field
        }
        if (serverFieldErrors.containsKey(field)) {
            serverFieldErrors = serverFieldErrors - field
        }
        if (serverFieldErrors.isEmpty()) {
            formBanner = null
            suggestLogin = false
        }
    }

    AuthFormScreen(scrollable = true) {
        Column(verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.sm)) {
            Text("Glagolitsa", style = MaterialTheme.typography.headlineLarge)
            Text(
                text = "Регистрация",
                style = MaterialTheme.typography.titleMedium,
                color = GlagolitsaColors.TextSecondary,
            )
            Text(
                text = "После регистрации на устройстве создаётся ключ шифрования для защищённых сообщений.",
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.TextTertiary,
            )
        }

        AuthFormFields {
            GlagolitsaInput(
                value = username,
                onValueChange = {
                    username = RegistrationValidation.sanitizeRegistrationUsernameInput(it)
                    clearFieldError(RegistrationValidation.Field.USERNAME)
                },
                placeholder = "Логин латиницей",
                modifier = Modifier.fillMaxWidth(),
                size = GlagolitsaInputSize.Default,
                variant = GlagolitsaInputVariant.Username,
                enabled = !loading,
                errorMessage = fieldErrors[RegistrationValidation.Field.USERNAME]
                    ?: serverFieldErrors[RegistrationValidation.Field.USERNAME],
            )
            Text(
                text = "Логин — только английские буквы для входа. Имя «Татьяна» можно задать позже в профиле.",
                style = MaterialTheme.typography.labelSmall,
                color = GlagolitsaColors.TextTertiary,
                modifier = Modifier.padding(bottom = GlagolitsaSpacing.xs),
            )

            GlagolitsaInput(
                value = email,
                onValueChange = {
                    email = it
                    clearFieldError(RegistrationValidation.Field.EMAIL)
                },
                placeholder = "Email (необязательно)",
                modifier = Modifier.fillMaxWidth(),
                size = GlagolitsaInputSize.Default,
                variant = GlagolitsaInputVariant.Email,
                enabled = !loading,
                errorMessage = fieldErrors[RegistrationValidation.Field.EMAIL]
                    ?: serverFieldErrors[RegistrationValidation.Field.EMAIL],
            )

            GlagolitsaInput(
                value = password,
                onValueChange = {
                    password = RegistrationValidation.sanitizeCredentialInput(it)
                    clearFieldError(RegistrationValidation.Field.PASSWORD)
                },
                placeholder = "Пароль",
                modifier = Modifier.fillMaxWidth(),
                size = GlagolitsaInputSize.Default,
                variant = GlagolitsaInputVariant.Password,
                enabled = !loading,
                errorMessage = fieldErrors[RegistrationValidation.Field.PASSWORD]
                    ?: serverFieldErrors[RegistrationValidation.Field.PASSWORD],
            )

            GlagolitsaInput(
                value = passwordConfirm,
                onValueChange = {
                    passwordConfirm = RegistrationValidation.sanitizeCredentialInput(it)
                    clearFieldError(RegistrationValidation.Field.PASSWORD_CONFIRM)
                },
                placeholder = "Повторите пароль",
                modifier = Modifier.fillMaxWidth(),
                size = GlagolitsaInputSize.Default,
                variant = GlagolitsaInputVariant.Password,
                enabled = !loading,
                errorMessage = fieldErrors[RegistrationValidation.Field.PASSWORD_CONFIRM],
            )
        }

        formBanner?.let { banner ->
            Text(
                text = banner,
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.OrnamentGold,
            )
        }

        AuthRaisedPillButton(
            onClick = {
                scope.launch {
                    loading = true
                    progress = null
                    clearServerFeedback()
                    val form = RegistrationValidation.Form(
                        username = username,
                        email = email,
                        password = password,
                        passwordConfirm = passwordConfirm,
                    )
                    val clientErrors = RegistrationValidation.validate(form)
                    if (clientErrors.isNotEmpty()) {
                        fieldErrors = clientErrors
                        loading = false
                        return@launch
                    }
                    fieldErrors = emptyMap()
                    runCatching {
                        repository.register(form) { phase -> progress = phase }
                    }.onSuccess {
                        onRegistered()
                    }.onFailure { throwable ->
                        when (throwable) {
                            is RegistrationValidationException -> {
                                fieldErrors = throwable.fieldErrors
                            }
                            else -> {
                                val uiError = throwable.registrationUiError()
                                if (uiError.field != null) {
                                    serverFieldErrors = mapOf(uiError.field to uiError.message)
                                }
                                formBanner = uiError.bannerHint
                                    ?: if (uiError.field == null) uiError.message else null
                                suggestLogin = uiError.suggestLogin
                            }
                        }
                    }
                    loading = false
                    progress = null
                }
            },
            enabled = !loading &&
                username.isNotBlank() &&
                password.isNotBlank() &&
                passwordConfirm.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("register-submit"),
        ) {
            Text(
                when (progress) {
                    RegisterProgress.PROOF_OF_WORK -> "Проверка безопасности..."
                    RegisterProgress.CREATING_ACCOUNT -> "Создание аккаунта..."
                    null -> if (loading) "Проверка и создание..." else "Создать аккаунт"
                },
            )
        }

        AuthInlineTextButton(
            prefix = if (suggestLogin) "Логин $username уже есть." else "Уже есть аккаунт?",
            action = "Войти",
            onClick = { onOpenLogin(username.trim().takeIf { it.isNotBlank() }) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = GlagolitsaSpacing.xs),
        )
    }
}
