// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glagolitsa.ui.theme.GlagolitsaColors

private enum class SecureHistoryMode {
    ENABLE,
    RESTORE,
}

/**
 * Secure history backup UI for ordinary users:
 * - Enable: create a one-time recovery code, keep a copy of chats safely.
 * - Restore: enter that code on a new phone (not the login password).
 */
@Composable
fun SecureHistoryDialog(
    loading: Boolean,
    enabled: Boolean,
    cloudPresent: Boolean,
    cloudInfoLoading: Boolean,
    shownRecoveryKey: String?,
    error: String?,
    preferRestore: Boolean,
    onDismiss: () -> Unit,
    onEnable: () -> Unit,
    onRestore: (recoveryKey: String) -> Unit,
) {
    var mode by remember(preferRestore) {
        mutableStateOf(if (preferRestore) SecureHistoryMode.RESTORE else SecureHistoryMode.ENABLE)
    }
    var restoreKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Защищённая история", color = GlagolitsaColors.TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Сохраните копию переписки, чтобы вернуть её на новом телефоне.\n\n" +
                        "Чаты уходят в облако в зашифрованном виде — прочитать их без вашего " +
                        "кода восстановления нельзя, даже на сервере. Код не совпадает с " +
                        "паролем входа: его нужно записать и хранить у себя. Без кода " +
                        "историю на новом устройстве не восстановить.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.TextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { mode = SecureHistoryMode.ENABLE },
                        enabled = !loading,
                    ) {
                        Text(
                            "Включить",
                            color = if (mode == SecureHistoryMode.ENABLE) {
                                GlagolitsaColors.AccentRed
                            } else {
                                GlagolitsaColors.TextSecondary
                            },
                        )
                    }
                    TextButton(
                        onClick = { mode = SecureHistoryMode.RESTORE },
                        enabled = !loading,
                    ) {
                        Text(
                            "Восстановить",
                            color = if (mode == SecureHistoryMode.RESTORE) {
                                GlagolitsaColors.AccentRed
                            } else {
                                GlagolitsaColors.TextSecondary
                            },
                        )
                    }
                }
                when (mode) {
                    SecureHistoryMode.ENABLE -> {
                        Text(
                            text = if (enabled) {
                                "Защита уже включена на этом телефоне. " +
                                    "Копия переписки обновляется автоматически."
                            } else {
                                "Мы создадим код восстановления и сохраним " +
                                    "зашифрованную копию ваших чатов.\n\n" +
                                    "Запишите код и никому его не показывайте — " +
                                    "он понадобится, если смените телефон."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = GlagolitsaColors.TextSecondary,
                        )
                        shownRecoveryKey?.let { key ->
                            Text(
                                text = "Ваш код восстановления — сохраните его сейчас:\n$key",
                                style = MaterialTheme.typography.bodySmall,
                                color = GlagolitsaColors.OrnamentGold,
                            )
                        }
                    }
                    SecureHistoryMode.RESTORE -> {
                        when {
                            cloudInfoLoading -> Text(
                                "Ищем сохранённую копию…",
                                style = MaterialTheme.typography.bodySmall,
                                color = GlagolitsaColors.TextSecondary,
                            )
                            cloudPresent -> Text(
                                "Найдена копия переписки. Введите код восстановления " +
                                    "со старого телефона — не пароль от входа в приложение.",
                                style = MaterialTheme.typography.bodySmall,
                                color = GlagolitsaColors.OrnamentGold,
                            )
                            else -> Text(
                                "Сохранённой копии пока нет. " +
                                    "Сначала включите защиту на старом телефоне " +
                                    "и запишите код восстановления.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        OutlinedTextField(
                            value = restoreKey,
                            onValueChange = { restoreKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Код восстановления") },
                            singleLine = true,
                            enabled = !loading && cloudPresent,
                        )
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (mode) {
                SecureHistoryMode.ENABLE -> {
                    TextButton(
                        onClick = onEnable,
                        enabled = !loading && shownRecoveryKey == null,
                    ) {
                        Text(
                            when {
                                loading -> "Включаем…"
                                enabled -> "Обновить копию"
                                else -> "Включить защиту"
                            },
                        )
                    }
                }
                SecureHistoryMode.RESTORE -> {
                    TextButton(
                        onClick = { onRestore(restoreKey.trim()) },
                        enabled = !loading &&
                            cloudPresent &&
                            restoreKey.replace(" ", "").length >= 32,
                    ) {
                        Text(if (loading) "Восстанавливаем…" else "Восстановить чаты")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}
