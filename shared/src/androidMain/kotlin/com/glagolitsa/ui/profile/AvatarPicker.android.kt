// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_AVATAR_BASE64_CHARS = 120_000

@OptIn(ExperimentalEncodingApi::class)
@Composable
actual fun rememberAvatarPicker(onResult: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceBytes by remember { mutableStateOf<ByteArray?>(null) }
    var cropVisible by remember { mutableStateOf(false) }

    fun showCropFor(uri: Uri?) {
        if (uri == null) {
            onResult(null)
            return
        }

        scope.launch {
            val bytes = readAvatarSourceBytes(context, uri)
            if (bytes == null) {
                onResult(null)
                return@launch
            }
            sourceBytes = bytes
            cropVisible = true
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
        onResult = ::showCropFor,
    )

    if (cropVisible) {
        val bytes = sourceBytes
        if (bytes != null) {
            AvatarCropDialog(
                sourceBytes = bytes,
                onDismiss = {
                    cropVisible = false
                    sourceBytes = null
                    onResult(null)
                },
                onConfirm = { cropSelection ->
                    cropVisible = false
                    sourceBytes = null
                    scope.launch {
                        val dataUrl = withContext(Dispatchers.IO) {
                            runCatching {
                                val processed = AvatarImageProcessor.processForUpload(
                                    sourceBytes = bytes,
                                    cropSelection = cropSelection,
                                ) ?: return@runCatching null
                                val encoded = Base64.encode(processed)
                                if (encoded.length > MAX_AVATAR_BASE64_CHARS) return@runCatching null
                                "data:image/jpeg;base64,$encoded"
                            }.getOrNull()
                        }
                        onResult(dataUrl)
                    }
                },
            )
        }
    }

    return remember(galleryLauncher) {
        { galleryLauncher.launch("image/*") }
    }
}

private suspend fun readAvatarSourceBytes(context: Context, uri: Uri): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            }
        }.getOrNull()
    }
