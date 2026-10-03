// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.Message
import com.glagolitsa.model.MessageDeliverySemantics
import com.glagolitsa.model.MessageDeliveryTick
import com.glagolitsa.model.MessageSendAlert
import com.glagolitsa.ui.layout.ChatLayout
import com.glagolitsa.ui.theme.GlagolitsaColors
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.chat_send_pending
import org.jetbrains.compose.resources.painterResource

/**
 * Own-message delivery footer.
 *
 * **Ticks** (ladder): PENDING (clock) · SENT (✓) · READ (✓✓)
 * **Alert** (outside ladder): FAILED → ⚠ Повторить
 *
 * See [MessageDeliverySemantics.presentation].
 */
@Composable
fun MessageDeliveryIndicator(
    message: Message,
    tint: Color,
    timeColor: Color,
    onRetryFailed: ((Message) -> Unit)?,
    sendError: String? = null,
    modifier: Modifier = Modifier,
) {
    val presentation = MessageDeliverySemantics.presentation(message)
    val pendingError = sendErrorLabel(sendError).takeIf { presentation.tick == MessageDeliveryTick.PENDING }
    when {
        presentation.alert == MessageSendAlert.FAILED -> {
            Text(
                text = "⚠ Повторить",
                modifier = modifier
                    .clickable(enabled = onRetryFailed != null) { onRetryFailed?.invoke(message) }
                    .testTag("chat-message-status-failed-retry")
                    .semantics { contentDescription = "Не отправлено, нажмите чтобы повторить" },
                style = MaterialTheme.typography.labelSmall,
                color = GlagolitsaColors.StatusError,
            )
        }
        presentation.tick == MessageDeliveryTick.PENDING && pendingError != null -> {
            Row(
                modifier = modifier
                    .testTag("chat-message-status-sending-error")
                    .semantics { contentDescription = "Отправляется: $pendingError" },
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PendingClockIcon(
                    tint = GlagolitsaColors.StatusError,
                    modifier = Modifier.size(ChatLayout.bubbleCheckIconSize),
                )
                Text(
                    text = pendingError,
                    style = MaterialTheme.typography.labelSmall,
                    color = GlagolitsaColors.StatusError,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 96.dp),
                )
            }
        }
        presentation.tick == MessageDeliveryTick.PENDING -> {
            PendingClockIcon(
                tint = timeColor,
                modifier = modifier
                    .size(ChatLayout.bubbleCheckIconSize)
                    .testTag("chat-message-status-sending")
                    .semantics { contentDescription = "Отправляется" },
            )
        }
        presentation.tick == MessageDeliveryTick.READ -> {
            DoubleCheckIcon(
                tint = tint,
                modifier = modifier
                    .size(ChatLayout.bubbleCheckIconSize)
                    .testTag("chat-message-status-read")
                    .semantics { contentDescription = "Прочитано" },
            )
        }
        presentation.tick == MessageDeliveryTick.SENT -> {
            SingleCheckIcon(
                tint = tint,
                modifier = modifier
                    .size(ChatLayout.bubbleCheckIconSize)
                    .testTag("chat-message-status-sent")
                    .semantics { contentDescription = "Отправлено" },
            )
        }
    }
}

internal fun sendErrorLabel(raw: String?): String? =
    TransientNetworkErrors.compactSendErrorLabel(raw)

/** Clock-with-retry mark — distinct from ✓ / ✓✓ so “sending” is never mistaken for sent/read. */
@Composable
private fun PendingClockIcon(tint: Color, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.chat_send_pending),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier,
    )
}

@Composable
private fun SingleCheckIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawLine(
            color = tint,
            start = Offset(size.width * 0.18f, size.height * 0.58f),
            end = Offset(size.width * 0.4f, size.height * 0.8f),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.4f, size.height * 0.8f),
            end = Offset(size.width * 0.82f, size.height * 0.28f),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun DoubleCheckIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawLine(
            color = tint,
            start = Offset(size.width * 0.12f, size.height * 0.56f),
            end = Offset(size.width * 0.32f, size.height * 0.76f),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.32f, size.height * 0.76f),
            end = Offset(size.width * 0.58f, size.height * 0.32f),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.42f, size.height * 0.56f),
            end = Offset(size.width * 0.62f, size.height * 0.76f),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.62f, size.height * 0.76f),
            end = Offset(size.width * 0.88f, size.height * 0.32f),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
