// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import com.glagolitsa.platform.iosRemoveFile
import com.glagolitsa.platform.iosWriteTemporaryFile
import com.glagolitsa.platform.toNSData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.Foundation.NSURL
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIView
import platform.UIKit.UIViewContentMode

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
actual fun AttachmentAnimatedGif(
    bytes: ByteArray,
    contentDescription: String?,
    modifier: Modifier,
) {
    if (bytes.isEmpty()) return
    val image = rememberDecodedImage(bytes) ?: return
    UIKitView(
        modifier = modifier.fillMaxSize(),
        factory = {
            UIImageView().apply {
                contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
                this.image = image
            }
        },
        update = { view ->
            view.image = image
        },
    )
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
actual fun AttachmentVideoPlayer(
    bytes: ByteArray,
    mimeType: String?,
    contentDescription: String?,
    modifier: Modifier,
) {
    if (bytes.isEmpty()) return
    val playback = rememberTemporaryVideoPlayback(bytes, mimeType) ?: return
    val (url, player) = playback
    DisposableEffect(player) {
        player.play()
        onDispose {
            player.pause()
            runCatching {
                iosRemoveFile(url)
            }
        }
    }
    UIKitView(
        modifier = modifier.fillMaxSize(),
        factory = {
            val view = UIView()
            val layer = AVPlayerLayer.playerLayerWithPlayer(player)
            layer.videoGravity = AVLayerVideoGravityResizeAspect
            view.layer.addSublayer(layer)
            view
        },
        update = { view ->
            val layer = view.layer.sublayers?.firstOrNull() as? AVPlayerLayer
            layer?.frame = view.bounds
        },
    )
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
private fun rememberDecodedImage(bytes: ByteArray): UIImage? {
    var image by remember { mutableStateOf<UIImage?>(null) }
    LaunchedEffect(bytes.size, bytes.contentHashCode()) {
        image = null
        image = withContext(Dispatchers.Default) {
            UIImage.imageWithData(bytes.toNSData())
        }
    }
    return image
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
private fun rememberTemporaryVideoPlayback(
    bytes: ByteArray,
    mimeType: String?,
): Pair<NSURL, AVPlayer>? {
    var playback by remember { mutableStateOf<Pair<NSURL, AVPlayer>?>(null) }
    LaunchedEffect(bytes.size, bytes.contentHashCode(), mimeType) {
        val previous = playback
        playback = null
        previous?.let { (url, player) ->
            player.pause()
            iosRemoveFile(url)
        }
        var pendingUrl: NSURL? = null
        try {
            pendingUrl = withContext(Dispatchers.Default) {
                val ext = videoFileExtension(mimeType)
                iosWriteTemporaryFile(bytes, "glagolitsa-video", ext)
            }
            coroutineContext.ensureActive()
            val url = pendingUrl ?: return@LaunchedEffect
            playback = url to AVPlayer.playerWithURL(url)
            pendingUrl = null
        } finally {
            iosRemoveFile(pendingUrl)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            playback?.let { (url, player) ->
                player.pause()
                iosRemoveFile(url)
            }
        }
    }
    return playback
}

private fun videoFileExtension(mimeType: String?): String = when {
    mimeType?.contains("webm", ignoreCase = true) == true -> "webm"
    mimeType?.contains("quicktime", ignoreCase = true) == true -> "mov"
    else -> "mp4"
}
