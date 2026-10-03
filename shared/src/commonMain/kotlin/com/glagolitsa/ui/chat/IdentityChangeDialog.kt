// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.glagolitsa.ui.theme.GlagolitsaColors

@Composable
fun IdentityChangeDialog(
    partnerName: String,
    onVerify: () -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Ключ безопасности изменился",
                color = GlagolitsaColors.TextPrimary,
            )
        },
        text = {
            Text(
                text = "Ключ шифрования $partnerName изменился. " +
                    "Сверьте код безопасности, прежде чем продолжить переписку.",
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text("Принять", color = GlagolitsaColors.AccentRedText)
            }
        },
        dismissButton = {
            TextButton(onClick = onVerify) {
                Text("Проверить код", color = GlagolitsaColors.TextPrimary)
            }
        },
    )
}
