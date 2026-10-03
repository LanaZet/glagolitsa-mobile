// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import com.glagolitsa.ui.components.avatarGlassLens
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glagolitsa.model.PresenceStatus
import com.glagolitsa.model.UserPresenceView
import com.glagolitsa.model.isVisibleInCall
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Platform decode for avatar bytes (Signal-style local decode + cache in composition).
 * Mattermost uses a separate image URL; we currently store data:image URLs in profile.
 */
expect fun decodeImageBytes(bytes: ByteArray): ImageBitmap?

/**
 * Decode for display with max edge (inSampleSize / scale).
 * Messenger gallery pattern: never decode multi-megapixel into grid cells.
 */
expect fun decodeImageBytesForDisplay(bytes: ByteArray, maxEdgePx: Int): ImageBitmap?

expect suspend fun loadRemoteImageBitmap(url: String): ImageBitmap?

/**
 * Single source of truth for presence **ring** color on avatars.
 * Used by [ProfileAvatar] everywhere (chat list, top bar, search, profile).
 * `null` = do not draw a status ring.
 */
fun UserPresenceView.presenceRingColor(): Color? = when {
    status == PresenceStatus.HIDDEN -> null
    isVisibleInCall() -> GlagolitsaColors.OrnamentGold
    status == PresenceStatus.ONLINE -> GlagolitsaColors.PresenceOnlineBright
    else -> null
}

@OptIn(ExperimentalEncodingApi::class)
suspend fun decodeDataUrlImageAsync(dataUrl: String): ImageBitmap? = withContext(Dispatchers.Default) {
    val commaIndex = dataUrl.indexOf(',')
    if (commaIndex == -1) return@withContext null
    runCatching {
        val bytes = Base64.decode(dataUrl.substring(commaIndex + 1))
        decodeImageBytes(bytes)
    }.getOrNull()
}

suspend fun loadAvatarImageAsync(avatarUrl: String): ImageBitmap? {
    val url = avatarUrl.trim()
    return when {
        url.startsWith("data:image") -> decodeDataUrlImageAsync(url)
        url.startsWith("http://") || url.startsWith("https://") -> loadRemoteImageBitmap(url)
        else -> null
    }
}

/**
 * Avatar disc: photo when [avatarUrl] is a data:image URI, else high-contrast monogram.
 * Monogram font scales with [size] so list/top-bar letters stay visible (unlike displaySmall on 40.dp).
 *
 * **Presence UI is centralized here:** pass [presenceColor] from [presenceRingColor]
 * (or null). Status is always a **ring around the avatar**, never a corner dot.
 */
@Composable
fun ProfileAvatar(
    avatarUrl: String?,
    fallbackLabel: String,
    modifier: Modifier = Modifier,
    size: Dp = 112.dp,
    presenceColor: Color? = null,
    showPresenceRing: Boolean = true,
) {
    var imageBitmap by remember(avatarUrl) { mutableStateOf<ImageBitmap?>(null) }
    var decoding by remember(avatarUrl) { mutableStateOf(false) }

    LaunchedEffect(avatarUrl) {
        val url = avatarUrl?.trim().orEmpty()
        if (url.isBlank()) {
            imageBitmap = null
            decoding = false
            return@LaunchedEffect
        }
        decoding = true
        try {
            imageBitmap = loadAvatarImageAsync(url)
        } finally {
            decoding = false
        }
    }

    val monogram = remember(fallbackLabel) {
        fallbackLabel.trim().take(2).ifBlank { "?" }.uppercase()
    }
    // ~38% of disc diameter — readable on 40.dp list avatars and 112.dp profile.
    val monogramSp = (size.value * 0.38f).coerceIn(11f, 42f).sp
    // Middle ground: readable ring without a thick “frame” eating the face.
    // (Was 6–9% inset / 7.5% stroke → too wide; 4.5% / 1.6dp → too faint.)
    val ringInset = if (presenceColor != null && showPresenceRing) {
        (size.value * 0.055f).coerceIn(2.5f, 7f).dp
    } else {
        0.dp
    }
    val ringStroke = if (presenceColor != null && showPresenceRing) {
        (size.value * 0.05f).coerceIn(2.2f, 4.5f).dp
    } else {
        0.dp
    }

    Box(
        modifier = modifier
            .size(size)
            .then(
                if (presenceColor != null && showPresenceRing) {
                    Modifier.drawBehind {
                        val min = this.size.minDimension
                        val strokePx = ringStroke.toPx()
                        val ringRadius = min * 0.5f - strokePx * 0.65f
                        val neon = presenceColor
                        // Soft white-tint for the neon core (reads as light on green/gold).
                        val neonCore = Color(
                            red = (neon.red * 0.55f + 0.45f).coerceIn(0f, 1f),
                            green = (neon.green * 0.55f + 0.45f).coerceIn(0f, 1f),
                            blue = (neon.blue * 0.55f + 0.45f).coerceIn(0f, 1f),
                            alpha = 1f,
                        )

                        // Outer neon bloom — wide, low alpha (doesn't thicken the hard edge).
                        drawCircle(
                            color = neon.copy(alpha = 0.10f),
                            radius = ringRadius,
                            style = Stroke(width = strokePx * 4.2f),
                        )
                        drawCircle(
                            color = neon.copy(alpha = 0.18f),
                            radius = ringRadius,
                            style = Stroke(width = strokePx * 2.8f),
                        )
                        drawCircle(
                            color = neon.copy(alpha = 0.32f),
                            radius = ringRadius,
                            style = Stroke(width = strokePx * 1.9f),
                        )

                        // Dark rail for contrast on light photos.
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.40f),
                            radius = ringRadius,
                            style = Stroke(width = strokePx * 1.25f),
                        )

                        // Solid ring body.
                        drawCircle(
                            color = neon.copy(alpha = 0.96f),
                            radius = ringRadius,
                            style = Stroke(width = strokePx),
                        )

                        // Bright neon edge along the stroke (thin inner/outer highlight).
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    0.72f to Color.Transparent,
                                    0.88f to neonCore.copy(alpha = 0.55f),
                                    0.94f to neon.copy(alpha = 0.85f),
                                    1f to neonCore.copy(alpha = 0.35f),
                                ),
                                center = center,
                                radius = min * 0.5f,
                            ),
                            radius = ringRadius,
                            style = Stroke(width = strokePx * 0.55f),
                        )
                        drawCircle(
                            color = neonCore.copy(alpha = 0.55f),
                            radius = ringRadius,
                            style = Stroke(width = strokePx * 0.35f),
                        )
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(ringInset)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            GlagolitsaColors.AvatarGradientTop,
                            GlagolitsaColors.AvatarGradientBottom,
                        ),
                    ),
                )
                .avatarGlassLens(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                decoding -> CircularProgressIndicator(
                    modifier = Modifier.size((size.value * 0.35f).coerceIn(14f, 28f).dp),
                    color = GlagolitsaColors.AccentRed,
                    strokeWidth = 2.dp,
                )
                imageBitmap != null -> Image(
                    bitmap = imageBitmap!!,
                    contentDescription = "Аватар",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center,
                )
                else -> Text(
                    text = monogram,
                    color = Color.White.copy(alpha = 0.96f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = monogramSp,
                    maxLines = 1,
                )
            }
        }
    }
}
