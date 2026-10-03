// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import android.graphics.ImageDecoder
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.glagolitsa.ui.profile.decodeImageBytes
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
actual fun AttachmentAnimatedGif(
    bytes: ByteArray,
    contentDescription: String?,
    modifier: Modifier,
) {
    if (bytes.isEmpty()) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val context = LocalContext.current
        val gifFile = rememberTemporaryPlaybackFile(
            bytes = bytes,
            directory = context.cacheDir,
            extension = "gif",
            prefix = "attachment-gif",
        ) ?: return
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    this.contentDescription = contentDescription
                    runCatching {
                        val source = ImageDecoder.createSource(gifFile)
                        val drawable = ImageDecoder.decodeDrawable(source)
                        setImageDrawable(drawable)
                        (drawable as? AnimatedImageDrawable)?.apply {
                            repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                            start()
                        }
                    }
                }
            },
            update = { view ->
                view.contentDescription = contentDescription
            },
        )
    } else {
        StillImageFallback(bytes = bytes, contentDescription = contentDescription, modifier = modifier)
    }
}

@Composable
actual fun AttachmentVideoPlayer(
    bytes: ByteArray,
    mimeType: String?,
    contentDescription: String?,
    modifier: Modifier,
) {
    if (bytes.isEmpty()) return
    val context = LocalContext.current
    val playbackFile = rememberTemporaryPlaybackFile(
        bytes = bytes,
        directory = context.cacheDir,
        extension = videoFileExtension(mimeType),
        prefix = "attachment-video",
    ) ?: return
    val holder = remember { MediaPlayerHolder() }
    DisposableEffect(playbackFile) {
        onDispose {
            holder.release()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                this.contentDescription = contentDescription
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        holder.release()
                        val playbackSurface = Surface(surface)
                        val mediaPlayer = MediaPlayer()
                        holder.player = mediaPlayer
                        holder.surface = playbackSurface
                        runCatching {
                            mediaPlayer.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                    .build(),
                            )
                            mediaPlayer.setDataSource(playbackFile.absolutePath)
                            mediaPlayer.setSurface(playbackSurface)
                            mediaPlayer.isLooping = false
                            mediaPlayer.setOnPreparedListener { prepared ->
                                prepared.start()
                            }
                            mediaPlayer.setOnErrorListener { _, _, _ ->
                                holder.release()
                                true
                            }
                            mediaPlayer.prepareAsync()
                        }.onFailure {
                            holder.release()
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        holder.release()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        },
        update = { view ->
            view.contentDescription = contentDescription
        },
    )
}

private class MediaPlayerHolder {
    var player: MediaPlayer? = null
    var surface: Surface? = null

    fun release() {
        val current = player
        val currentSurface = surface
        player = null
        surface = null
        if (current != null) {
            runCatching { current.stop() }
            runCatching { current.release() }
        }
        runCatching { currentSurface?.release() }
    }
}

private fun videoFileExtension(mimeType: String?): String = when {
    mimeType?.contains("webm", ignoreCase = true) == true -> "webm"
    mimeType?.contains("quicktime", ignoreCase = true) == true -> "mov"
    else -> "mp4"
}

@Composable
private fun rememberTemporaryPlaybackFile(
    bytes: ByteArray,
    directory: File,
    extension: String,
    prefix: String,
): File? {
    var playbackFile by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(bytes.size, bytes.contentHashCode(), directory.absolutePath, extension, prefix) {
        val previous = playbackFile
        playbackFile = null
        withContext(Dispatchers.IO) {
            previous?.delete()
        }
        val created = withContext(Dispatchers.IO) {
            File(directory, "$prefix-${UUID.randomUUID()}.$extension").also { file ->
                file.writeBytes(bytes)
            }
        }
        if (currentCoroutineContext().isActive) {
            playbackFile = created
        } else {
            withContext(Dispatchers.IO) {
                created.delete()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val file = playbackFile
            playbackFile = null
            file?.delete()
        }
    }
    return playbackFile
}

@Composable
private fun StillImageFallback(
    bytes: ByteArray,
    contentDescription: String?,
    modifier: Modifier,
) {
    var bitmap by remember(bytes.size, bytes.contentHashCode()) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bytes.size, bytes.contentHashCode()) {
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
