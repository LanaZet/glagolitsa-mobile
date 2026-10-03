// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.channel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.glagolitsa.media.OPEN_MEDIA_FEED_EDGE
import com.glagolitsa.media.OPEN_MEDIA_GALLERY_EDGE
import com.glagolitsa.media.OpenMediaImageLoader
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.ui.profile.decodeImageBytesForDisplay

@Composable
fun rememberOpenMediaLoader(repository: MessengerRepository): OpenMediaImageLoader =
    remember(repository) {
        OpenMediaImageLoader(
            download = { id -> repository.downloadChannelPhoto(id) },
            decode = { bytes, edge -> decodeImageBytesForDisplay(bytes, edge) },
            maxConcurrent = 3,
        )
    }

/**
 * Lazy open-media image: loads only while composed (LazyGrid/List dispose off-screen).
 * Prefer [thumbFileId] for gallery; full file for feed.
 */
@Composable
fun ChannelOpenImage(
    loader: OpenMediaImageLoader,
    fileId: String,
    thumbFileId: String? = null,
    preferThumb: Boolean,
    maxEdgePx: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val loadId = if (preferThumb) thumbFileId?.takeIf { it.isNotBlank() } ?: fileId else fileId
    var bitmap by remember(loadId, maxEdgePx) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(loadId) { mutableStateOf(false) }

    LaunchedEffect(loadId, maxEdgePx) {
        failed = false
        bitmap = null
        val bmp = loader.load(loadId, maxEdgePx)
        if (bmp == null) {
            if (preferThumb && loadId != fileId) {
                bitmap = loader.load(fileId, maxEdgePx)
            }
            if (bitmap == null) failed = true
        } else {
            bitmap = bmp
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
            failed -> Box(Modifier.fillMaxSize())
            else -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

object ChannelImageEdges {
    val Gallery = OPEN_MEDIA_GALLERY_EDGE
    val Feed = OPEN_MEDIA_FEED_EDGE
}
