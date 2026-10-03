// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glagolitsa.auth.RegistrationValidation
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.security.LocalAuthAvailability
import com.glagolitsa.security.LocalAuthResult
import com.glagolitsa.security.LocalAuthenticator
import com.glagolitsa.session.BiometricUnlockCandidate
import com.glagolitsa.ui.api.authLoginMessage
import com.glagolitsa.ui.components.AuthFormFields
import com.glagolitsa.ui.components.AuthFormScreen
import com.glagolitsa.ui.components.AuthInlineTextButton
import com.glagolitsa.ui.components.AuthLoginPillButton
import com.glagolitsa.ui.components.GlagolitsaInput
import com.glagolitsa.ui.components.GlagolitsaInputSize
import com.glagolitsa.ui.components.GlagolitsaInputVariant
import com.glagolitsa.ui.components.SlavicPillOutlinedButton
import com.glagolitsa.ui.profile.ForgotPasswordDialog
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaSpacing
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.auth_fingerprint
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

@Composable
fun LoginScreen(
    repository: MessengerRepository,
    localAuthenticator: LocalAuthenticator,
    initialUsername: String? = null,
    onLoggedIn: () -> Unit,
    onOpenRegister: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val devShortcuts = devShortcutsEnabled()
    // Show baked API label whenever set (public builds used to lie via silent 10.0.2.2 rewrite).
    val devApiUrl = remember { devApiBaseUrlLabel() }
    val devAccounts = remember(devShortcuts) {
        if (devShortcuts) devShortcutAccounts() else emptyList()
    }
    var username by remember(initialUsername, devAccounts) {
        mutableStateOf(
            RegistrationValidation.sanitizeCredentialInput(initialUsername.orEmpty()).ifBlank {
                devAccounts.firstOrNull()?.username.orEmpty()
            },
        )
    }
    var password by remember(devAccounts) {
        mutableStateOf(RegistrationValidation.sanitizeCredentialInput(devAccounts.firstOrNull()?.password.orEmpty()))
    }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    var biometricCandidate by remember { mutableStateOf<BiometricUnlockCandidate?>(null) }
    var biometricAvailability by remember { mutableStateOf(LocalAuthAvailability.Unknown) }
    var biometricError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repository, localAuthenticator) {
        biometricCandidate = runCatching { repository.biometricUnlockCandidate() }.getOrNull()
        biometricAvailability = runCatching { localAuthenticator.availability() }
            .getOrDefault(LocalAuthAvailability.Unknown)
    }

    fun clearErrors() {
        usernameError = null
        passwordError = null
        biometricError = null
    }

    AuthFormScreen {
        Column(verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.sm)) {
            Text("Glagolitsa", style = MaterialTheme.typography.headlineLarge)
            Text(
                text = "Вход",
                style = MaterialTheme.typography.titleMedium,
                color = GlagolitsaColors.TextSecondary,
            )
            Text(
                text = "Логин из регистрации (@username), не имя в профиле.",
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.TextTertiary,
            )
        }

        // Always show which API this binary hits (catches public/local mix-ups).
        devApiUrl?.let { apiUrl ->
            Text(
                text = "API: $apiUrl",
                style = MaterialTheme.typography.labelSmall,
                color = GlagolitsaColors.TextTertiary,
            )
        }

        if (devAccounts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.sm)) {
                Text(
                    text = "Только для разработки: быстрый вход (local)",
                    style = MaterialTheme.typography.labelSmall,
                    color = GlagolitsaColors.TextTertiary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.sm),
                ) {
                    devAccounts.forEach { account ->
                        SlavicPillOutlinedButton(
                            onClick = {
                                scope.launch {
                                    loading = true
                                    clearErrors()
                                    username = RegistrationValidation.sanitizeCredentialInput(account.username)
                                    password = RegistrationValidation.sanitizeCredentialInput(account.password)
                                    runCatching {
                                        repository.login(username, password)
                                    }.onSuccess {
                                        onLoggedIn()
                                    }.onFailure { throwable ->
                                        passwordError = throwable.authLoginMessage()
                                    }
                                    loading = false
                                }
                            },
                            enabled = !loading,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Войти как ${account.label}")
                        }
                    }
                }
            }
        }

        AuthFormFields {
            GlagolitsaInput(
                value = username,
                onValueChange = {
                    username = RegistrationValidation.sanitizeCredentialInput(it)
                    clearErrors()
                },
                placeholder = "Логин",
                modifier = Modifier.fillMaxWidth(),
                size = GlagolitsaInputSize.Default,
                variant = GlagolitsaInputVariant.Username,
                enabled = !loading,
                errorMessage = usernameError,
            )
            GlagolitsaInput(
                value = password,
                onValueChange = {
                    password = RegistrationValidation.sanitizeCredentialInput(it)
                    passwordError = null
                },
                placeholder = "Пароль",
                modifier = Modifier.fillMaxWidth(),
                size = GlagolitsaInputSize.Default,
                variant = GlagolitsaInputVariant.Password,
                enabled = !loading,
                errorMessage = passwordError,
            )
        }

        AuthLoginPillButton(
            onClick = {
                scope.launch {
                    loading = true
                    clearErrors()
                    if (username.isBlank()) {
                        usernameError = "Введите логин"
                        loading = false
                        return@launch
                    }
                    if (password.isBlank()) {
                        passwordError = "Введите пароль"
                        loading = false
                        return@launch
                    }
                    runCatching {
                        repository.login(
                            RegistrationValidation.normalizeUsername(username),
                            password,
                        )
                    }.onSuccess {
                        onLoggedIn()
                    }.onFailure { throwable ->
                        passwordError = throwable.authLoginMessage()
                    }
                    loading = false
                }
            },
            enabled = !loading && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Войти")
        }

        // Only when «Вход по отпечатку» is enabled for a stored session.
        if (biometricCandidate != null) {
            FingerprintLoginCue(
                availability = biometricAvailability,
                loading = loading,
                error = biometricError,
                onClick = {
                    scope.launch {
                        clearErrors()
                        if (biometricAvailability != LocalAuthAvailability.Available) {
                            biometricError = biometricUnavailableMessage(biometricAvailability)
                            return@launch
                        }
                        loading = true
                        when (val result = localAuthenticator.authenticate("Подтвердите вход в сохраненный аккаунт")) {
                            LocalAuthResult.Success -> {
                                runCatching { repository.restoreBiometricUnlockedSession() }
                                    .onSuccess { restored ->
                                        if (restored) {
                                            onLoggedIn()
                                        } else {
                                            biometricError = "Сохраненная сессия недоступна. Войдите паролем."
                                        }
                                    }
                                    .onFailure { throwable ->
                                        biometricError = throwable.authLoginMessage()
                                    }
                            }
                            LocalAuthResult.Cancelled -> Unit
                            is LocalAuthResult.Failed -> {
                                biometricError = result.message ?: "Не удалось подтвердить отпечаток"
                            }
                        }
                        loading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AuthInlineTextButton(
            prefix = "",
            action = "Забыли пароль?",
            onClick = { showForgotPasswordDialog = true },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        )

        AuthInlineTextButton(
            prefix = "Нет аккаунта?",
            action = "Зарегистрироваться",
            onClick = onOpenRegister,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = GlagolitsaSpacing.xs),
        )

        if (showForgotPasswordDialog) {
            ForgotPasswordDialog(
                repository = repository,
                localAuthenticator = localAuthenticator,
                initialUsername = username,
                onDismiss = { showForgotPasswordDialog = false },
                onCompleted = { recoveredUsername ->
                    username = recoveredUsername
                    password = ""
                },
            )
        }
    }
}

@Composable
private fun FingerprintLoginCue(
    availability: LocalAuthAvailability,
    loading: Boolean,
    error: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactive = !loading
    val sensorReady = availability == LocalAuthAvailability.Available
    Column(
        modifier = modifier.padding(top = GlagolitsaSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GlagolitsaSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(Res.drawable.auth_fingerprint),
                contentDescription = "Вход по отпечатку пальца",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(width = 56.dp, height = 72.dp)
                    .alpha(
                        when {
                            !interactive -> 0.48f
                            !sensorReady -> 0.72f
                            else -> 1f
                        },
                    )
                    .clip(MaterialTheme.shapes.small)
                    .then(
                        if (interactive) {
                            Modifier
                                .clickable(onClick = onClick)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "Войти по отпечатку пальца"
                                }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.AccentRed,
            )
        }
    }
}

private fun biometricUnavailableMessage(availability: LocalAuthAvailability): String =
    when (availability) {
        LocalAuthAvailability.NoBiometricEnrolled -> "Добавьте отпечаток в настройках телефона или войдите паролем."
        LocalAuthAvailability.Unsupported -> "На этом устройстве вход по отпечатку недоступен."
        LocalAuthAvailability.HardwareUnavailable -> "Датчик отпечатка временно недоступен."
        LocalAuthAvailability.SecurityUpdateRequired -> "Для входа по отпечатку требуется обновление безопасности."
        LocalAuthAvailability.Unknown -> "Проверьте отпечаток на устройстве или войдите паролем."
        LocalAuthAvailability.Available -> ""
    }
