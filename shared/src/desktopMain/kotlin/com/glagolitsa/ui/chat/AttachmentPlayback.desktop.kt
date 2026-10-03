// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.glagolitsa.ui.profile.decodeImageBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun AttachmentAnimatedGif(
    bytes: ByteArray,
    contentDescription: String?,
    modifier: Modifier,
) {
    StillPlayback(bytes = bytes, contentDescription = contentDescription, modifier = modifier)
}

@Composable
actual fun AttachmentVideoPlayer(
    bytes: ByteArray,
    mimeType: String?,
    contentDescription: String?,
    modifier: Modifier,
) {
    StillPlayback(bytes = bytes, contentDescription = contentDescription, modifier = modifier)
}

@Composable
private fun StillPlayback(
    bytes: ByteArray,
    contentDescription: String?,
    modifier: Modifier,
) {
    var bitmap by remember(bytes.size, bytes.contentHashCode()) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bytes.size, bytes.contentHashCode()) {
        if (bytes.isEmpty()) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.Default) { decodeImageBytes(bytes) }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
