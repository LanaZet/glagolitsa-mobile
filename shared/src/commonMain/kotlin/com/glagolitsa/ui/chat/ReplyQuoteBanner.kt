// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glagolitsa.repository.AttachmentPreview
import com.glagolitsa.ui.components.GlagolitsaButtonCloseIcon
import com.glagolitsa.ui.components.concaveSurface
import com.glagolitsa.ui.components.convexSurface
import com.glagolitsa.ui.profile.decodeImageBytes
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class ReplyQuoteSurface {
    Raised,
    Flat,
    /** Compact strip for placement inside the chat message field. */
    Embedded,
}

@Composable
internal fun ReplyQuoteBanner(
    title: String,
    body: String,
    actionLabel: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    titleColor: Color = GlagolitsaColors.TextPrimary,
    attachmentPreview: AttachmentPreview? = null,
    surface: ReplyQuoteSurface = ReplyQuoteSurface.Flat,
    onClear: (() -> Unit)? = null,
) {
    val embedded = surface == ReplyQuoteSurface.Embedded
    val shape = RoundedCornerShape(if (embedded) 12.dp else 16.dp)
    val baseModifier = if (embedded) {
        modifier
            .fillMaxWidth()
            .clip(shape)
    } else {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(shape)
    }
    val surfaceModifier = when (surface) {
        ReplyQuoteSurface.Raised -> baseModifier
            .convexSurface(
                shape = shape,
                cornerRadius = 16.dp,
                baseColor = GlagolitsaColors.Surface800.copy(alpha = 0.88f),
            )
            .border(0.75.dp, GlagolitsaColors.GlassBorder, shape)
        ReplyQuoteSurface.Flat -> baseModifier
            .background(GlagolitsaColors.Surface800.copy(alpha = 0.66f))
            .border(0.7.dp, Color.White.copy(alpha = 0.12f), shape)
        ReplyQuoteSurface.Embedded -> baseModifier
            .concaveSurface(
                shape = shape,
                cornerRadius = 12.dp,
                baseColor = GlagolitsaColors.Surface800.copy(alpha = 0.48f),
                focused = true,
            )
            .border(0.65.dp, Color.Black.copy(alpha = 0.18f), shape)
    }

    Row(
        modifier = surfaceModifier.padding(
            start = if (embedded) 8.dp else 10.dp,
            top = if (embedded) 6.dp else 8.dp,
            end = if (embedded) 4.dp else 7.dp,
            bottom = if (embedded) 6.dp else 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(if (embedded) 7.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(if (embedded) 2.5.dp else 3.dp)
                .height(if (embedded) 30.dp else 40.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(accentColor),
        )
        if (attachmentPreview?.isImage == true) {
            ReplyQuoteThumbnail(
                preview = attachmentPreview,
                compact = embedded,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (embedded) 0.dp else 2.dp),
        ) {
            Text(
                text = "$actionLabel: $title",
                style = if (embedded) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.labelMedium
                },
                color = titleColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = body.normalizeQuotePreview(),
                style = if (embedded) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = GlagolitsaColors.TextSecondary.copy(alpha = if (embedded) 0.88f else 1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        onClear?.let { clear ->
            Box(
                modifier = Modifier
                    .size(if (embedded) 28.dp else 32.dp)
                    .clip(CircleShape)
                    .background(
                        if (embedded) {
                            Color.White.copy(alpha = 0.06f)
                        } else {
                            GlagolitsaColors.Surface700.copy(alpha = 0.66f)
                        },
                    )
                    .clickable(onClick = clear),
                contentAlignment = Alignment.Center,
            ) {
                GlagolitsaButtonCloseIcon(
                    tint = GlagolitsaColors.TextSecondary,
                    modifier = Modifier.size(if (embedded) 14.dp else 16.dp),
                )
            }
        }
    }
}

@Composable
private fun ReplyQuoteThumbnail(
    preview: AttachmentPreview,
    compact: Boolean = false,
) {
    val size = if (compact) 30.dp else 42.dp
    val corner = if (compact) 8.dp else 11.dp
    val shape = RoundedCornerShape(corner)
    val contentKey = remember(preview.messageId, preview.bytes.contentHashCode()) {
        preview.messageId to preview.bytes.contentHashCode()
    }
    var imageBitmap by remember(contentKey) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(contentKey) {
        imageBitmap = withContext(Dispatchers.Default) {
            decodeImageBytes(preview.bytes)
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .concaveSurface(
                shape = shape,
                cornerRadius = corner,
                baseColor = GlagolitsaColors.Surface700.copy(alpha = 0.6f),
            )
            .border(0.65.dp, GlagolitsaColors.OrnamentRed.copy(alpha = 0.34f), shape),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = imageBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = preview.fileName ?: "Фото",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = "Фото",
                style = MaterialTheme.typography.labelSmall,
                color = GlagolitsaColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
