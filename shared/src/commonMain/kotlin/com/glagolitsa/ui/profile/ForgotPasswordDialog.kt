// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glagolitsa.auth.AccountRecoveryKey
import com.glagolitsa.auth.RegistrationValidation
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.security.LocalAuthenticator
import com.glagolitsa.ui.api.authRecoveryMessage
import com.glagolitsa.ui.components.GlagolitsaInput
import com.glagolitsa.ui.components.GlagolitsaInputSize
import com.glagolitsa.ui.components.GlagolitsaInputVariant
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PasswordRecoveryOption {
    RecoveryKey,
    TrustedDevice,
    Passkey,
}

private enum class PasswordRecoveryStep {
    Choose,
    EnterKey,
    TrustedWait,
    NewPassword,
    PasskeyConfirm,
    Done,
}

@Composable
fun ForgotPasswordDialog(
    repository: MessengerRepository,
    localAuthenticator: LocalAuthenticator,
    initialUsername: String,
    onDismiss: () -> Unit,
    onCompleted: (username: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedOption by remember { mutableStateOf(PasswordRecoveryOption.RecoveryKey) }
    var infoOption by remember { mutableStateOf<PasswordRecoveryOption?>(null) }
    var step by remember { mutableStateOf(PasswordRecoveryStep.Choose) }
    var username by remember {
        mutableStateOf(RegistrationValidation.sanitizeCredentialInput(initialUsername))
    }
    var recoveryKey by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newPasswordConfirm by remember { mutableStateOf("") }
    var recoveryToken by remember { mutableStateOf("") }
    var challengeId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var recoveredUsername by remember { mutableStateOf("") }

    fun fail(throwable: Throwable) {
        error = throwable.authRecoveryMessage()
        loading = false
    }

    if (step == PasswordRecoveryStep.TrustedWait && challengeId.isNotBlank()) {
        LaunchedEffect(challengeId) {
            while (true) {
                delay(2_000)
                val poll = runCatching { repository.pollTrustedAccountRecovery(challengeId) }
                    .getOrElse { throwable ->
                        error = throwable.authRecoveryMessage()
                        return@LaunchedEffect
                    }
                when (poll.status) {
                    "approved" -> {
                        val token = poll.recovery_token.orEmpty()
                        if (token.isNotBlank()) {
                            recoveryToken = token
                            recoveredUsername = poll.username.orEmpty().ifBlank { username }
                            step = PasswordRecoveryStep.NewPassword
                            return@LaunchedEffect
                        }
                    }
                    "expired" -> {
                        error = "Запрос на подтверждение истёк. Начните снова."
                        return@LaunchedEffect
                    }
                    "used" -> {
                        error = "Запрос уже был использован. Начните восстановление заново."
                        return@LaunchedEffect
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Восстановление пароля", color = GlagolitsaColors.TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    PasswordRecoveryStep.Choose -> {
                        Text(
                            text = "Выберите удобный способ:",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlagolitsaColors.TextSecondary,
                        )
                        RecoveryOptionRow(
                            label = "По recovery key",
                            subtitle = "Если вы заранее сохранили ключ восстановления.",
                            selected = selectedOption == PasswordRecoveryOption.RecoveryKey,
                            onSelect = { selectedOption = PasswordRecoveryOption.RecoveryKey },
                            onInfo = { infoOption = PasswordRecoveryOption.RecoveryKey },
                        )
                        RecoveryOptionRow(
                            label = "Через доверенное устройство",
                            subtitle = "Подтверждение со старого устройства, где вы уже входили.",
                            selected = selectedOption == PasswordRecoveryOption.TrustedDevice,
                            onSelect = { selectedOption = PasswordRecoveryOption.TrustedDevice },
                            onInfo = { infoOption = PasswordRecoveryOption.TrustedDevice },
                        )
                        RecoveryOptionRow(
                            label = "Через passkey",
                            subtitle = "Быстрое подтверждение через биометрию или ключ устройства.",
                            selected = selectedOption == PasswordRecoveryOption.Passkey,
                            onSelect = { selectedOption = PasswordRecoveryOption.Passkey },
                            onInfo = { infoOption = PasswordRecoveryOption.Passkey },
                        )
                    }
                    PasswordRecoveryStep.EnterKey -> {
                        Text(
                            text = if (selectedOption == PasswordRecoveryOption.TrustedDevice) {
                                "Введите логин аккаунта, запрос появится на доверенном устройстве."
                            } else {
                                "Ключ восстановления хранится только у вас. Сервер видит лишь его хэш."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = GlagolitsaColors.TextSecondary,
                        )
                        GlagolitsaInput(
                            value = username,
                            onValueChange = {
                                username = RegistrationValidation.sanitizeCredentialInput(it)
                                error = null
                            },
                            placeholder = "Логин",
                            modifier = Modifier.fillMaxWidth(),
                            size = GlagolitsaInputSize.Default,
                            variant = GlagolitsaInputVariant.Username,
                            enabled = !loading,
                        )
                        if (selectedOption != PasswordRecoveryOption.TrustedDevice) {
                            GlagolitsaInput(
                                value = recoveryKey,
                                onValueChange = {
                                    recoveryKey = it
                                    error = null
                                },
                                placeholder = "Ключ восстановления",
                                modifier = Modifier.fillMaxWidth(),
                                size = GlagolitsaInputSize.Default,
                                enabled = !loading,
                            )
                        }
                    }
                    PasswordRecoveryStep.TrustedWait -> {
                        Text(
                            text = "На другом устройстве, где вы уже вошли, откройте Профиль → Восстановление и подтвердите запрос.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlagolitsaColors.TextSecondary,
                        )
                        Text(
                            text = "Ожидаем подтверждение…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlagolitsaColors.OrnamentGold,
                        )
                    }
                    PasswordRecoveryStep.NewPassword -> {
                        Text(
                            text = "Задайте новый пароль. После этого войдите им обычным способом.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlagolitsaColors.TextSecondary,
                        )
                        GlagolitsaInput(
                            value = newPassword,
                            onValueChange = {
                                newPassword = RegistrationValidation.sanitizeCredentialInput(it)
                                error = null
                            },
                            placeholder = "Новый пароль",
                            modifier = Modifier.fillMaxWidth(),
                            size = GlagolitsaInputSize.Default,
                            variant = GlagolitsaInputVariant.Password,
                            enabled = !loading,
                        )
                        GlagolitsaInput(
                            value = newPasswordConfirm,
                            onValueChange = {
                                newPasswordConfirm = RegistrationValidation.sanitizeCredentialInput(it)
                                error = null
                            },
                            placeholder = "Повторите пароль",
                            modifier = Modifier.fillMaxWidth(),
                            size = GlagolitsaInputSize.Default,
                            variant = GlagolitsaInputVariant.Password,
                            enabled = !loading,
                        )
                    }
                    PasswordRecoveryStep.PasskeyConfirm -> {
                        Text(
                            text = "Подтвердите ключ телефона. После этого задайте новый пароль.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlagolitsaColors.TextSecondary,
                        )
                        GlagolitsaInput(
                            value = username,
                            onValueChange = {
                                username = RegistrationValidation.sanitizeCredentialInput(it)
                                error = null
                            },
                            placeholder = "Логин",
                            modifier = Modifier.fillMaxWidth(),
                            size = GlagolitsaInputSize.Default,
                            variant = GlagolitsaInputVariant.Username,
                            enabled = !loading,
                        )
                    }
                    PasswordRecoveryStep.Done -> {
                        Text(
                            text = "Пароль обновлён. Войдите новым паролем. Старый ключ восстановления больше не действует — создайте новый в профиле.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlagolitsaColors.TextSecondary,
                        )
                    }
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = GlagolitsaColors.AccentRed,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, enabled = !loading) {
                    Text(
                        text = if (step == PasswordRecoveryStep.Done) "Закрыть" else "Отмена",
                        color = GlagolitsaColors.TextSecondary,
                    )
                }
                if (step != PasswordRecoveryStep.Done && step != PasswordRecoveryStep.TrustedWait) {
                    TextButton(
                        enabled = !loading,
                        onClick = {
                            error = null
                            when (step) {
                                PasswordRecoveryStep.Choose -> {
                                    when (selectedOption) {
                                        PasswordRecoveryOption.RecoveryKey ->
                                            step = PasswordRecoveryStep.EnterKey
                                        PasswordRecoveryOption.Passkey ->
                                            step = PasswordRecoveryStep.PasskeyConfirm
                                        PasswordRecoveryOption.TrustedDevice -> {
                                            if (username.isBlank()) {
                                                step = PasswordRecoveryStep.EnterKey
                                            } else {
                                                loading = true
                                                scope.launch {
                                                    runCatching { repository.startTrustedAccountRecovery(username) }
                                                        .onSuccess { started ->
                                                            challengeId = started.challenge_id
                                                            recoveredUsername = username
                                                            step = PasswordRecoveryStep.TrustedWait
                                                        }
                                                        .onFailure(::fail)
                                                    loading = false
                                                }
                                            }
                                        }
                                    }
                                }
                                PasswordRecoveryStep.EnterKey -> {
                                    if (selectedOption == PasswordRecoveryOption.TrustedDevice) {
                                        if (username.isBlank()) {
                                            error = "Введите логин"
                                            return@TextButton
                                        }
                                        loading = true
                                        scope.launch {
                                            runCatching { repository.startTrustedAccountRecovery(username) }
                                                .onSuccess { started ->
                                                    challengeId = started.challenge_id
                                                    recoveredUsername = username
                                                    step = PasswordRecoveryStep.TrustedWait
                                                }
                                                .onFailure(::fail)
                                            loading = false
                                        }
                                        return@TextButton
                                    }
                                    AccountRecoveryKey.validate(recoveryKey)?.let {
                                        error = it
                                        return@TextButton
                                    }
                                    if (username.isBlank()) {
                                        error = "Введите логин"
                                        return@TextButton
                                    }
                                    loading = true
                                    scope.launch {
                                        runCatching { repository.verifyAccountRecovery(username, recoveryKey) }
                                            .onSuccess { ticket ->
                                                recoveryToken = ticket.recovery_token.orEmpty()
                                                recoveredUsername = ticket.username.orEmpty().ifBlank { username }
                                                if (recoveryToken.isBlank()) {
                                                    error = "Сервер не выдал одноразовый токен"
                                                } else {
                                                    step = PasswordRecoveryStep.NewPassword
                                                }
                                            }
                                            .onFailure(::fail)
                                        loading = false
                                    }
                                }
                                PasswordRecoveryStep.PasskeyConfirm -> {
                                    if (username.isBlank()) {
                                        error = "Введите логин"
                                        return@TextButton
                                    }
                                    loading = true
                                    scope.launch {
                                        runCatching {
                                            repository.recoverWithPasskey(username, localAuthenticator)
                                        }.onSuccess { ticket ->
                                            recoveryToken = ticket.recovery_token.orEmpty()
                                            recoveredUsername = ticket.username.orEmpty().ifBlank { username }
                                            if (recoveryToken.isBlank()) {
                                                error = "Сервер не выдал одноразовый токен"
                                            } else {
                                                step = PasswordRecoveryStep.NewPassword
                                            }
                                        }.onFailure(::fail)
                                        loading = false
                                    }
                                }
                                PasswordRecoveryStep.NewPassword -> {
                                    val passwordError = RegistrationValidation.validatePassword(newPassword)
                                    if (passwordError != null) {
                                        error = passwordError
                                        return@TextButton
                                    }
                                    if (newPassword != newPasswordConfirm) {
                                        error = "Пароли не совпадают"
                                        return@TextButton
                                    }
                                    loading = true
                                    scope.launch {
                                        runCatching {
                                            repository.completeAccountRecovery(recoveryToken, newPassword)
                                        }.onSuccess { result ->
                                            recoveredUsername = result.username.orEmpty().ifBlank { recoveredUsername }
                                            step = PasswordRecoveryStep.Done
                                            onCompleted(recoveredUsername)
                                        }.onFailure(::fail)
                                        loading = false
                                    }
                                }
                                PasswordRecoveryStep.TrustedWait,
                                PasswordRecoveryStep.Done -> Unit
                            }
                        },
                    ) {
                        Text(
                            text = when (step) {
                                PasswordRecoveryStep.NewPassword -> "Сохранить пароль"
                                PasswordRecoveryStep.PasskeyConfirm -> "Подтвердить passkey"
                                PasswordRecoveryStep.EnterKey -> "Продолжить"
                                else -> "Продолжить"
                            },
                            color = GlagolitsaColors.AccentRed,
                        )
                    }
                }
            }
        },
    )

    infoOption?.let { option ->
        AlertDialog(
            onDismissRequest = { infoOption = null },
            title = {
                Text(
                    text = when (option) {
                        PasswordRecoveryOption.RecoveryKey -> "По recovery key"
                        PasswordRecoveryOption.TrustedDevice -> "Через доверенное устройство"
                        PasswordRecoveryOption.Passkey -> "Через passkey"
                    },
                    color = GlagolitsaColors.TextPrimary,
                )
            },
            text = {
                Text(
                    text = when (option) {
                        PasswordRecoveryOption.RecoveryKey ->
                            "Используйте код восстановления, который вы сохранили заранее. Это основной способ вернуть доступ, если пароль забыт."
                        PasswordRecoveryOption.TrustedDevice ->
                            "Подтверждение через устройство, где вы уже входили раньше. Удобно, если код восстановления сейчас недоступен."
                        PasswordRecoveryOption.Passkey ->
                            "Вход по passkey (биометрия/ключ устройства) без ввода пароля. Быстрый и безопасный способ подтвердить личность."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlagolitsaColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { infoOption = null }) {
                    Text("Понятно", color = GlagolitsaColors.AccentRed)
                }
            },
        )
    }
}

@Composable
private fun RecoveryOptionRow(
    label: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onInfo: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSelect,
                ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                color = if (selected) GlagolitsaColors.AccentRed else GlagolitsaColors.TextSecondary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                color = GlagolitsaColors.TextTertiary,
            )
        }
        TextButton(
            onClick = onInfo,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(28.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .border(
                        width = 1.dp,
                        color = GlagolitsaColors.TextSecondary,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "?",
                    color = GlagolitsaColors.TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
