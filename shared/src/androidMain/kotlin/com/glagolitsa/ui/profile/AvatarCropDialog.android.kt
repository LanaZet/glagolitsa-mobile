// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AvatarCropDialog(
    sourceBytes: ByteArray,
    onDismiss: () -> Unit,
    onConfirm: (AvatarCropSelection) -> Unit,
) {
    val previewBitmap by produceState<Bitmap?>(initialValue = null, key1 = sourceBytes) {
        value = withContext(Dispatchers.Default) {
            AvatarImageProcessor.decodePreviewBitmap(sourceBytes)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            color = GlagolitsaColors.Surface800,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Выберите область аватара",
                    style = MaterialTheme.typography.titleLarge,
                    color = GlagolitsaColors.TextPrimary,
                )
                Text(
                    text = "Перетащите фото внутри рамки и увеличьте его жестом или ползунком ниже.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlagolitsaColors.TextSecondary,
                )

                when (val bitmap = previewBitmap) {
                    null -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = GlagolitsaColors.AccentRed)
                    }

                    else -> AvatarCropEditor(bitmap = bitmap, onDismiss = onDismiss, onConfirm = onConfirm)
                }
            }
        }
    }
}

@Composable
private fun AvatarCropEditor(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    onConfirm: (AvatarCropSelection) -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var frameSizePx by remember { mutableStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    fun maxOffsets(currentZoom: Float): Pair<Float, Float> {
        if (frameSizePx <= 0f) return 0f to 0f
        val baseScale = max(frameSizePx / bitmap.width.toFloat(), frameSizePx / bitmap.height.toFloat())
        val renderedWidth = bitmap.width * baseScale * currentZoom
        val renderedHeight = bitmap.height * baseScale * currentZoom
        val maxOffsetX = max(0f, (renderedWidth - frameSizePx) / 2f)
        val maxOffsetY = max(0f, (renderedHeight - frameSizePx) / 2f)
        return maxOffsetX to maxOffsetY
    }

    fun clampOffsets(currentZoom: Float) {
        val (maxOffsetX, maxOffsetY) = maxOffsets(currentZoom)
        offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
        offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
    }

    BoxWithConstraints {
        val frameSizeModifier = Modifier
            .size(maxWidth.coerceAtMost(360.dp) * 0.82f)
            .aspectRatio(1f)

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = frameSizeModifier
                    .align(Alignment.CenterHorizontally)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.24f))
                    .onSizeChanged { frameSizePx = it.width.toFloat() }
                    .pointerInput(bitmap) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            val nextZoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                            zoom = nextZoom
                            offsetX += pan.x
                            offsetY += pan.y
                            clampOffsets(nextZoom)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    bitmap = image,
                    contentDescription = "Фото для аватара",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .graphicsLayer {
                            translationX = offsetX
                            translationY = offsetY
                            scaleX = zoom
                            scaleY = zoom
                        },
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .border(2.dp, Color.White.copy(alpha = 0.92f), CircleShape),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Масштаб",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlagolitsaColors.TextSecondary,
                    )
                    Text(
                        text = "${(zoom * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlagolitsaColors.TextPrimary,
                    )
                }
                Slider(
                    value = zoom,
                    onValueChange = { nextZoom ->
                        zoom = nextZoom
                        clampOffsets(nextZoom)
                    },
                    valueRange = 1f..4f,
                    colors = SliderDefaults.colors(
                        thumbColor = GlagolitsaColors.AccentRed,
                        activeTrackColor = GlagolitsaColors.AccentRed,
                        activeTickColor = Color.Transparent,
                        inactiveTrackColor = GlagolitsaColors.Surface700,
                        inactiveTickColor = Color.Transparent,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "1x",
                        style = MaterialTheme.typography.labelSmall,
                        color = GlagolitsaColors.TextTertiary,
                    )
                    Text(
                        text = "4x",
                        style = MaterialTheme.typography.labelSmall,
                        color = GlagolitsaColors.TextTertiary,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val selection = avatarCropSelection(
                            bitmapWidth = bitmap.width,
                            bitmapHeight = bitmap.height,
                            frameSizePx = frameSizePx,
                            zoom = zoom,
                            offsetXPx = offsetX,
                            offsetYPx = offsetY,
                        )
                        onConfirm(selection)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = frameSizePx > 0f,
                ) {
                    Text("Использовать")
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = GlagolitsaColors.Surface700),
                ) {
                    Text("Отмена")
                }
            }
        }
    }
}
