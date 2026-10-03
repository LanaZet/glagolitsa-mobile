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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.glagolitsa.backup.CloudBackupInfo
import com.glagolitsa.ui.theme.GlagolitsaColors

private enum class BackupDialogMode {
    EXPORT,
    RESTORE,
}

@Composable
fun EncryptedBackupDialog(
    loading: Boolean,
    recoveryKey: String?,
    error: String?,
    cloudBackupInfo: CloudBackupInfo?,
    cloudInfoLoading: Boolean,
    onDismiss: () -> Unit,
    onExport: (CharArray) -> Unit,
    onRestore: (passphrase: CharArray, recoveryKey: String) -> Unit,
) {
    var mode by remember { mutableStateOf(BackupDialogMode.EXPORT) }
    var passphrase by remember { mutableStateOf("") }
    var restoreRecoveryKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Зашифрованная копия", color = GlagolitsaColors.TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { mode = BackupDialogMode.EXPORT },
                        enabled = !loading,
                    ) {
                        Text(
                            text = "Экспорт",
                            color = if (mode == BackupDialogMode.EXPORT) {
                                GlagolitsaColors.AccentRed
                            } else {
                                GlagolitsaColors.TextSecondary
                            },
                        )
                    }
                    TextButton(
                        onClick = { mode = BackupDialogMode.RESTORE },
                        enabled = !loading,
                    ) {
                        Text(
                            text = "Восстановить",
                            color = if (mode == BackupDialogMode.RESTORE) {
                                GlagolitsaColors.AccentRed
                            } else {
                                GlagolitsaColors.TextSecondary
                            },
                        )
                    }
                }

                when (mode) {
                    BackupDialogMode.EXPORT -> {
                        Text(
                            text = "Экспорт SQLCipher-базы и Signal store под паролем. " +
                                "Копия автоматически загружается на сервер. " +
                                "Сохраните recovery key отдельно от пароля.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlagolitsaColors.TextSecondary,
                        )
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Пароль резервной копии") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !loading && recoveryKey == null,
                        )
                        recoveryKey?.let { key ->
                            Text(
                                text = "Recovery key:\n$key",
                                style = MaterialTheme.typography.bodySmall,
                                color = GlagolitsaColors.OrnamentGold,
                            )
                        }
                    }

                    BackupDialogMode.RESTORE -> {
                        Text(
                            text = "Восстановление локального кэша и Signal store с сервера. " +
                                "Нужны пароль и recovery key от последнего экспорта. " +
                                "Приложение перезапустится после восстановления.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlagolitsaColors.TextSecondary,
                        )
                        when {
                            cloudInfoLoading -> {
                                Text(
                                    text = "Проверяем копию на сервере...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GlagolitsaColors.TextSecondary,
                                )
                            }
                            cloudBackupInfo != null -> {
                                Text(
                                    text = "На сервере: ${cloudBackupInfo.createdAt}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GlagolitsaColors.OrnamentGold,
                                )
                            }
                            else -> {
                                Text(
                                    text = "На сервере нет сохранённой копии",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Пароль резервной копии") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !loading,
                        )
                        OutlinedTextField(
                            value = restoreRecoveryKey,
                            onValueChange = { restoreRecoveryKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Recovery key") },
                            singleLine = true,
                            enabled = !loading,
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
                BackupDialogMode.EXPORT -> {
                    TextButton(
                        onClick = { onExport(passphrase.toCharArray()) },
                        enabled = !loading && recoveryKey == null && passphrase.length >= 8,
                    ) {
                        Text(if (loading) "Экспорт..." else "Экспортировать")
                    }
                }
                BackupDialogMode.RESTORE -> {
                    TextButton(
                        onClick = { onRestore(passphrase.toCharArray(), restoreRecoveryKey.trim()) },
                        enabled = !loading &&
                            cloudBackupInfo != null &&
                            passphrase.length >= 8 &&
                            restoreRecoveryKey.isNotBlank(),
                    ) {
                        Text(if (loading) "Восстановление..." else "Восстановить")
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