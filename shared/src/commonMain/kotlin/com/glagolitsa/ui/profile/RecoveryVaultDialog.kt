// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.TrustedRecoveryPendingItem
import com.glagolitsa.ui.theme.GlagolitsaColors
import com.glagolitsa.ui.theme.GlagolitsaShapes

private enum class RecoveryVaultAction {
    StartRecovery,
    EnterRecoveryKey,
    UsePasskey,
}

@Composable
fun RecoveryVaultDialog(
    plan: RecoveryVaultPlan,
    onDismiss: () -> Unit,
    createdKey: String? = null,
    pendingChallenges: List<TrustedRecoveryPendingItem> = emptyList(),
    loading: Boolean = false,
    error: String? = null,
    onCreateKey: (() -> Unit)? = null,
    onCreatePasskey: (() -> Unit)? = null,
    onApprove: ((String) -> Unit)? = null,
) {
    var selectedAction by remember { mutableStateOf(RecoveryVaultAction.StartRecovery) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Восстановление доступа", color = GlagolitsaColors.TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = plan.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.TextSecondary,
                )
                Text(
                    text = plan.statusLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = GlagolitsaColors.OrnamentGold,
                )
                Text(
                    text = plan.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlagolitsaColors.TextPrimary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    plan.factors.forEach { factor ->
                        RecoveryVaultFactorRow(factor = factor)
                    }
                }
                Text(
                    text = plan.nextStep,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.TextSecondary,
                )
                if (pendingChallenges.isNotEmpty()) {
                    Text(
                        text = "Другое устройство просит восстановить пароль",
                        style = MaterialTheme.typography.labelLarge,
                        color = GlagolitsaColors.TextPrimary,
                    )
                    pendingChallenges.forEach { challenge ->
                        TextButton(
                            onClick = { onApprove?.invoke(challenge.challenge_id) },
                            enabled = !loading && onApprove != null,
                        ) {
                            Text("Подтвердить запрос", color = GlagolitsaColors.AccentRed)
                        }
                    }
                }
                createdKey?.let { key ->
                    Text(
                        text = "Сохраните этот ключ в надёжном месте. Сервер его больше не покажет:",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlagolitsaColors.TextSecondary,
                    )
                    Text(
                        text = key,
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlagolitsaColors.OrnamentGold,
                    )
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecoveryVaultPill("На телефоне")
                    RecoveryVaultPill("В облаке")
                    RecoveryVaultPill("Ключ телефона")
                    RecoveryVaultPill("Доверенное устройство")
                }
                Text(
                    text = "Что можно сделать",
                    style = MaterialTheme.typography.labelLarge,
                    color = GlagolitsaColors.TextPrimary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RecoveryVaultActionButton(
                        label = "Начать восстановление",
                        selected = selectedAction == RecoveryVaultAction.StartRecovery,
                        onClick = { selectedAction = RecoveryVaultAction.StartRecovery },
                    )
                    RecoveryVaultActionButton(
                        label = "Ввести код восстановления",
                        selected = selectedAction == RecoveryVaultAction.EnterRecoveryKey,
                        onClick = { selectedAction = RecoveryVaultAction.EnterRecoveryKey },
                    )
                    RecoveryVaultActionButton(
                        label = "Войти ключом телефона",
                        selected = selectedAction == RecoveryVaultAction.UsePasskey,
                        onClick = { selectedAction = RecoveryVaultAction.UsePasskey },
                    )
                }
                Text(
                    text = when (selectedAction) {
                        RecoveryVaultAction.StartRecovery ->
                            "Шаг 1: проверяем, что обе половины ключа на месте и можно " +
                                "продолжить восстановление."
                        RecoveryVaultAction.EnterRecoveryKey ->
                            "Шаг 2: введите код восстановления со старого телефона, " +
                                "чтобы подтвердить, что это вы."
                        RecoveryVaultAction.UsePasskey ->
                            "Шаг 3: подтвердите вход отпечатком или Face ID — " +
                                "дополнительная проверка перед сменой пароля."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = GlagolitsaColors.TextSecondary,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onCreateKey != null && createdKey == null) {
                    TextButton(onClick = onCreateKey, enabled = !loading) {
                        Text("Создать ключ", color = GlagolitsaColors.AccentRed)
                    }
                }
                if (onCreatePasskey != null) {
                    TextButton(onClick = onCreatePasskey, enabled = !loading) {
                        Text("Подключить passkey", color = GlagolitsaColors.AccentRed)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Закрыть")
                }
            }
        },
    )
}

@Composable
private fun RecoveryVaultFactorRow(factor: RecoveryVaultFactor) {
    val toneColor = when (factor.state) {
        RecoveryVaultFactorState.Ready -> GlagolitsaColors.OrnamentGold
        RecoveryVaultFactorState.Missing -> MaterialTheme.colorScheme.error
        RecoveryVaultFactorState.Planned -> GlagolitsaColors.TextTertiary
    }
    Card(
        shape = GlagolitsaShapes.sm,
        colors = CardDefaults.cardColors(containerColor = GlagolitsaColors.Surface700.copy(alpha = 0.66f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = factor.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlagolitsaColors.TextPrimary,
                )
                Text(
                    text = factor.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = toneColor,
                )
            }
            Text(
                text = factor.hint,
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun RecoveryVaultPill(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = GlagolitsaColors.TextSecondary,
        modifier = Modifier
            .background(
                color = GlagolitsaColors.Surface700.copy(alpha = 0.8f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun RecoveryVaultActionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Text(
            text = label,
            color = if (selected) GlagolitsaColors.AccentRed else GlagolitsaColors.TextSecondary,
        )
    }
}
