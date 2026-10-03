// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glagolitsa.crypto.SafetyNumberInfo
import com.glagolitsa.model.Chat
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.security.SecureClipboard
import com.glagolitsa.security.SecureWindowEffect
import com.glagolitsa.ui.components.AppTopBar
import com.glagolitsa.ui.components.SlavicBackButton
import com.glagolitsa.ui.components.SlavicPillTextButton
import com.glagolitsa.ui.components.screenTopSafeArea
import com.glagolitsa.ui.safety.SafetyNumberQrCode
import com.glagolitsa.ui.theme.GlagolitsaColors

@Composable
fun SafetyNumberScreen(
    chat: Chat,
    partnerUserId: String,
    repository: MessengerRepository,
    onBack: () -> Unit,
) {
    var safetyNumber by remember { mutableStateOf<SafetyNumberInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    SecureWindowEffect()

    LaunchedEffect(partnerUserId) {
        loading = true
        error = null
        runCatching { repository.loadSafetyNumber(partnerUserId) }
            .onSuccess { safetyNumber = it }
            .onFailure { error = it.message }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenTopSafeArea()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppTopBar(
            title = "Код безопасности",
            subtitle = "Сверьте числа и QR с ${chat.title}",
            navigation = {
                SlavicBackButton(onClick = onBack)
            },
        )

        when {
            loading -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = GlagolitsaColors.AccentRed)
            }
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            safetyNumber != null -> {
                val info = safetyNumber!!
                SafetyNumberQrCode(
                    payload = info.qrPayload,
                    modifier = Modifier
                        .size(220.dp)
                        .align(Alignment.CenterHorizontally),
                    foreground = GlagolitsaColors.TextPrimary,
                    background = GlagolitsaColors.Surface800,
                )
                Text(
                    text = info.displayText,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                    color = GlagolitsaColors.TextPrimary,
                )
                SlavicPillTextButton(
                    onClick = {
                        SecureClipboard.copyWithAutoClear(
                            label = "safety-number",
                            text = info.displayText,
                        )
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text("Копировать", color = GlagolitsaColors.TextPrimary)
                }
            }
        }
    }
}
