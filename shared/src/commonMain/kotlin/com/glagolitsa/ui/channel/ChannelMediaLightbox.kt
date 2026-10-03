// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.channel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.glagolitsa.media.OPEN_MEDIA_GALLERY_EDGE
import com.glagolitsa.media.OPEN_MEDIA_LIGHTBOX_EDGE
import com.glagolitsa.media.OpenMediaImageLoader
import com.glagolitsa.model.ChannelMediaRef
import kotlinx.coroutines.launch

/**
 * Progressive lightbox with pinch-zoom and horizontal swipe.
 * Thumb → full upgrade; neighbors prefetched.
 * Comments entry is deferred for a later stage.
 */
@Composable
fun ChannelMediaLightbox(
    media: List<ChannelMediaRef>,
    startIndex: Int,
    loader: OpenMediaImageLoader,
    onDismiss: () -> Unit,
) {
    if (media.isEmpty()) {
        onDismiss()
        return
    }
    var index by remember(media, startIndex) {
        mutableStateOf(startIndex.coerceIn(0, media.lastIndex))
    }
    val current = media[index]

    LaunchedEffect(index, media) {
        val neighbors = listOfNotNull(
            media.getOrNull(index - 1),
            media.getOrNull(index + 1),
        )
        neighbors.forEach { ref ->
            launch {
                loader.prefetch(ref.displayFileId(preferThumb = true), OPEN_MEDIA_GALLERY_EDGE)
            }
        }
    }

    var thumb by remember(current.fileId) { mutableStateOf<ImageBitmap?>(null) }
    var full by remember(current.fileId) { mutableStateOf<ImageBitmap?>(null) }
    var loadingFull by remember(current.fileId) { mutableStateOf(true) }
    var scale by remember(current.fileId) { mutableFloatStateOf(1f) }
    var offset by remember(current.fileId) { mutableStateOf(Offset.Zero) }
    var dragAccum by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(current.fileId, current.thumbFileId) {
        thumb = null
        full = null
        loadingFull = true
        scale = 1f
        offset = Offset.Zero
        dragAccum = 0f
        val thumbId = current.thumbFileId?.takeIf { it.isNotBlank() } ?: current.fileId
        thumb = loader.load(thumbId, OPEN_MEDIA_GALLERY_EDGE)
        full = loader.load(current.fileId, OPEN_MEDIA_LIGHTBOX_EDGE)
        loadingFull = false
    }

    fun goPrev() {
        if (index > 0) {
            index -= 1
            scale = 1f
            offset = Offset.Zero
        }
    }

    fun goNext() {
        if (index < media.lastIndex) {
            index += 1
            scale = 1f
            offset = Offset.Zero
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f)),
    ) {
        val display = full ?: thumb
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(current.fileId, scale) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(1f, 4f)
                        scale = next
                        if (next > 1.01f) {
                            offset += pan
                        } else {
                            offset = Offset.Zero
                        }
                    }
                }
                .pointerInput(current.fileId, scale, index) {
                    // Horizontal swipe between photos when not zoomed.
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (scale <= 1.05f) {
                                when {
                                    dragAccum < -80f -> goNext()
                                    dragAccum > 80f -> goPrev()
                                }
                            }
                            dragAccum = 0f
                        },
                        onHorizontalDrag = { _, amount ->
                            if (scale <= 1.05f) {
                                dragAccum += amount
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.2f
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (display != null) {
                Image(
                    bitmap = display,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentScale = ContentScale.Fit,
                )
            } else {
                CircularProgressIndicator(color = Color.White)
            }
        }

        if (loadingFull && thumb != null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .size(20.dp),
                color = Color.White.copy(alpha = 0.7f),
                strokeWidth = 2.dp,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = "${index + 1} / ${media.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "←",
                    color = if (index > 0) Color.White else Color.White.copy(alpha = 0.3f),
                    modifier = Modifier
                        .clickable(enabled = index > 0) { goPrev() }
                        .padding(12.dp),
                )
                Text(
                    "Закрыть",
                    color = Color.White,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(12.dp),
                )
                Text(
                    "→",
                    color = if (index < media.lastIndex) Color.White else Color.White.copy(alpha = 0.3f),
                    modifier = Modifier
                        .clickable(enabled = index < media.lastIndex) { goNext() }
                        .padding(12.dp),
                )
            }
        }
    }
}
