// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.ui.platform.LocalContext
import com.glagolitsa.crypto.STREAMING_ATTACHMENT_MAGIC
import com.glagolitsa.media.AttachmentKind
import com.glagolitsa.media.resolveAttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val MAX_ATTACHMENT_BYTES = 100L * 1024L * 1024L
private const val KEY_BYTES = 32
private const val NONCE_BYTES = 12
private const val GCM_TAG_BITS = 128

private val allowedMimeTypes = setOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/heic",
    "video/mp4",
    "video/quicktime",
    "video/webm",
    "application/pdf",
    "application/zip",
    "application/x-zip-compressed",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "audio/mpeg",
    "audio/mp4",
    "audio/aac",
    "audio/wav",
    "audio/ogg",
    "text/plain",
)

private val allowedExtensions = setOf(
    "jpg", "jpeg", "png", "webp", "heic",
    "mp4", "mov", "webm",
    "pdf", "zip", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "mp3", "m4a", "aac", "wav", "ogg", "txt",
)

@Composable
actual fun rememberAttachmentPicker(onResult: (PickedAttachment?) -> Unit): AttachmentPickerActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val handleUri: (Uri, String?, String?) -> Unit = remember(context, scope, onResult) {
        { uri, fallbackFileName, fallbackMimeType ->
            scope.launch {
                val attachment = withContext(Dispatchers.IO) {
                    loadPickedAttachment(
                        uri = uri,
                        fallbackFileName = fallbackFileName,
                        fallbackMimeType = fallbackMimeType,
                        context = context,
                    )
                }
                onResult(attachment)
            }
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            onResult(null)
            return@rememberLauncherForActivityResult
        }

        handleUri(uri, null, null)
    }
    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) {
            onResult(null)
            return@rememberLauncherForActivityResult
        }

        handleUri(uri, null, null)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { captured ->
        val uri = cameraUri
        cameraUri = null
        if (!captured || uri == null) {
            onResult(null)
            return@rememberLauncherForActivityResult
        }

        handleUri(uri, "camera-${System.currentTimeMillis()}.jpg", "image/jpeg")
    }
    fun launchCamera() {
        runCatching {
            val uri = createCameraUri(context)
            cameraUri = uri
            cameraLauncher.launch(uri)
        }.onFailure {
            cameraUri = null
            onResult(
                PickedAttachment(
                    fileName = null,
                    mimeType = null,
                    errorMessage = "Не удалось открыть камеру",
                ),
            )
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            onResult(
                PickedAttachment(
                    fileName = null,
                    mimeType = null,
                    errorMessage = "Нет доступа к камере",
                ),
            )
        }
    }

    return remember(fileLauncher, mediaLauncher, cameraLauncher, cameraPermissionLauncher, context) {
        AttachmentPickerActions(
            pickPhotoOrVideo = {
                runCatching {
                    mediaLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                    )
                }.onFailure {
                    onResult(
                        PickedAttachment(
                            fileName = null,
                            mimeType = null,
                            errorMessage = "Не удалось открыть галерею",
                        ),
                    )
                }
            },
            pickFile = {
                runCatching {
                    fileLauncher.launch(fileMimeTypes)
                }.onFailure {
                    onResult(
                        PickedAttachment(
                            fileName = null,
                            mimeType = null,
                            errorMessage = "Не удалось открыть выбор файла",
                        ),
                    )
                }
            },
            openCamera = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    launchCamera()
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
        )
    }
}

private val fileMimeTypes = allowedMimeTypes
    .filterNot { it.startsWith("image/") || it.startsWith("video/") }
    .toTypedArray()

private fun loadPickedAttachment(
    uri: Uri,
    fallbackFileName: String?,
    fallbackMimeType: String?,
    context: android.content.Context,
): PickedAttachment? = runCatching {
    val resolver = context.contentResolver
    val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: fallbackFileName
    val sizeHint = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else -1L
        } ?: -1L
    if (sizeHint > MAX_ATTACHMENT_BYTES) {
        return@runCatching PickedAttachment(
            fileName = fileName,
            mimeType = null,
            errorMessage = "Файл слишком большой (максимум 100 МБ)",
        )
    }
    val mimeType = resolver.getType(uri)?.lowercase() ?: fallbackMimeType
    val extension = fileName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
    if (!isAllowedType(mimeType, extension)) {
        return@runCatching PickedAttachment(
            fileName = fileName,
            mimeType = mimeType,
            errorMessage = "Неподдерживаемый тип файла",
        )
    }
    val input = resolver.openInputStream(uri) ?: return@runCatching null
    val encrypted = encryptToPrivateSpool(
        input = input,
        maxBytes = MAX_ATTACHMENT_BYTES,
        mimeType = mimeType,
        extension = extension,
        root = File(context.filesDir, "media-cache").apply { mkdirs() },
    )
    if (encrypted.errorMessage != null) {
        return@runCatching PickedAttachment(
            fileName = fileName,
            mimeType = mimeType,
            errorMessage = encrypted.errorMessage,
        )
    }
    val mediaMetadata = extractPickedMediaMetadata(context, uri, mimeType, fileName)
    PickedAttachment(
        fileName = fileName,
        mimeType = mimeType,
        encryptedSpool = PickedEncryptedAttachmentSpool(
            path = encrypted.path ?: return@runCatching null,
            fileKey = encrypted.fileKey ?: return@runCatching null,
            encryptedSize = encrypted.encryptedSize,
            plaintextSize = encrypted.plaintextSize,
        ),
        kind = mediaMetadata.kind,
        width = mediaMetadata.width,
        height = mediaMetadata.height,
        durationMs = mediaMetadata.durationMs,
        thumbnailBytes = mediaMetadata.thumbnailBytes,
    )
}.getOrNull()

private fun extractPickedMediaMetadata(
    context: android.content.Context,
    uri: Uri,
    mimeType: String?,
    fileName: String?,
): PickedMediaMetadata {
    val kind = resolveAttachmentKind(mimeType, fileName)
    if (kind != AttachmentKind.VIDEO) {
        return PickedMediaMetadata(kind = kind)
    }
    return runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.use {
            it.setDataSource(context, uri)
            val width = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val durationMs = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val frame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                it.getScaledFrameAtTime(
                    -1,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    640,
                    640,
                )
            } else {
                it.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            PickedMediaMetadata(
                kind = kind,
                width = width,
                height = height,
                durationMs = durationMs,
                thumbnailBytes = frame?.toJpegBytes(maxEdgePx = 640),
            )
        }
    }.getOrDefault(PickedMediaMetadata(kind = kind))
}

private fun Bitmap.toJpegBytes(maxEdgePx: Int): ByteArray? {
    val max = maxOf(width, height)
    val bitmap = if (max > maxEdgePx && maxEdgePx > 0) {
        val scale = maxEdgePx.toFloat() / max.toFloat()
        Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        this
    }
    return ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 86, out)
        out.toByteArray().takeIf { it.isNotEmpty() }
    }.also {
        if (bitmap !== this) bitmap.recycle()
        recycle()
    }
}

private fun createCameraUri(context: android.content.Context): Uri {
    val cameraDir = File(context.cacheDir, "camera").apply { mkdirs() }
    val imageFile = File(cameraDir, "camera-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        imageFile,
    )
}

private fun encryptToPrivateSpool(
    input: java.io.InputStream,
    maxBytes: Long,
    mimeType: String?,
    extension: String?,
    root: File,
): PickerEncryptedSpoolResult {
    val random = SecureRandom()
    val fileKey = ByteArray(KEY_BYTES).also(random::nextBytes)
    val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
    val target = File(root, "picker-${UUID.randomUUID()}.bin")
    var plaintextSize = 0L
    return runCatching {
        input.use { source ->
            val firstChunk = ByteArray(DEFAULT_BUFFER_SIZE)
            val firstRead = source.read(firstChunk)
            if (firstRead <= 0) {
                return@runCatching PickerEncryptedSpoolResult(errorMessage = "Пустой файл нельзя отправить")
            }
            plaintextSize += firstRead
            if (plaintextSize > maxBytes) {
                return@runCatching PickerEncryptedSpoolResult(errorMessage = "Файл слишком большой (максимум 100 МБ)")
            }
            val firstBytes = firstChunk.copyOf(firstRead)
            if (!matchesMagic(firstBytes, mimeType, extension)) {
                return@runCatching PickerEncryptedSpoolResult(errorMessage = "Файл не прошел проверку безопасности")
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            target.outputStream().use { fileOut ->
                fileOut.write(STREAMING_ATTACHMENT_MAGIC)
                fileOut.write(nonce)
                CipherOutputStream(fileOut, cipher).use { cipherOut ->
                    cipherOut.write(firstBytes)
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        plaintextSize += read
                        if (plaintextSize > maxBytes) {
                            throw AttachmentPickerSizeException()
                        }
                        cipherOut.write(buffer, 0, read)
                    }
                }
            }
            PickerEncryptedSpoolResult(
                path = target.absolutePath,
                fileKey = fileKey,
                encryptedSize = target.length(),
                plaintextSize = plaintextSize,
            )
        }
    }.getOrElse { error ->
        if (target.exists()) target.delete()
        PickerEncryptedSpoolResult(
            errorMessage = if (error is AttachmentPickerSizeException) {
                "Файл слишком большой (максимум 100 МБ)"
            } else {
                "Не удалось подготовить файл"
            },
        )
    }
}

private fun isAllowedType(mimeType: String?, extension: String?): Boolean {
    if (mimeType != null && mimeType !in allowedMimeTypes) return false
    if (extension != null && extension !in allowedExtensions) return false
    return mimeType != null || extension != null
}

private fun matchesMagic(bytes: ByteArray, mimeType: String?, extension: String?): Boolean {
    val header = bytes.copyOfRange(0, minOf(bytes.size, 12))
    val jpeg = header.size >= 3 &&
        header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
    val png = header.size >= 8 &&
        header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() &&
        header[4] == 0x0D.toByte() && header[5] == 0x0A.toByte() && header[6] == 0x1A.toByte() && header[7] == 0x0A.toByte()
    val pdf = header.size >= 4 &&
        header[0] == 0x25.toByte() && header[1] == 0x50.toByte() && header[2] == 0x44.toByte() && header[3] == 0x46.toByte()
    val zip = header.size >= 4 &&
        header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
        (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte()) &&
        (header[3] == 0x04.toByte() || header[3] == 0x06.toByte() || header[3] == 0x08.toByte())
    val mp3 = header.size >= 3 &&
        ((header[0] == 0x49.toByte() && header[1] == 0x44.toByte() && header[2] == 0x33.toByte()) ||
            (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0))
    val wav = header.size >= 12 &&
        header[0] == 0x52.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte() && header[3] == 0x46.toByte() &&
        header[8] == 0x57.toByte() && header[9] == 0x41.toByte() && header[10] == 0x56.toByte() && header[11] == 0x45.toByte()
    val ogg = header.size >= 4 &&
        header[0] == 0x4F.toByte() && header[1] == 0x67.toByte() && header[2] == 0x67.toByte() && header[3] == 0x53.toByte()
    val mp4 = header.size >= 12 &&
        header[4] == 0x66.toByte() && header[5] == 0x74.toByte() && header[6] == 0x79.toByte() && header[7] == 0x70.toByte()
    val webm = header.size >= 4 &&
        header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() && header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()

    val expected = (mimeType ?: extension.orEmpty()).lowercase()
    return when {
        expected.contains("jpeg") || expected.contains("jpg") -> jpeg
        expected.contains("png") -> png
        expected.contains("webp") -> true
        expected.contains("heic") -> true
        expected.contains("pdf") -> pdf
        expected.contains("zip") || expected.contains("docx") || expected.contains("xlsx") || expected.contains("pptx") -> zip
        expected.contains("mp4") || expected.contains("quicktime") || expected.contains("mov") || expected.contains("m4a") || expected.contains("aac") -> mp4 || mp3
        expected.contains("webm") -> webm
        expected.contains("mpeg") || expected.contains("mp3") -> mp3
        expected.contains("wav") -> wav
        expected.contains("ogg") -> ogg
        expected.contains("txt") || expected.contains("plain") -> true
        else -> true
    }
}

private class AttachmentPickerSizeException : RuntimeException()

private data class PickerEncryptedSpoolResult(
    val path: String? = null,
    val fileKey: ByteArray? = null,
    val encryptedSize: Long = 0,
    val plaintextSize: Long = 0,
    val errorMessage: String? = null,
)

private data class PickedMediaMetadata(
    val kind: AttachmentKind,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val thumbnailBytes: ByteArray? = null,
)
