// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.platform.iosRemoveFile
import com.glagolitsa.platform.iosWriteTemporaryFile
import com.glagolitsa.platform.toNSData
import com.glagolitsa.repository.AttachmentPreview
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalForeignApi::class)
actual object AttachmentDownloadSaver {
    actual val isSupported: Boolean = true

    actual suspend fun saveImage(preview: AttachmentPreview): String = save(preview)

    actual suspend fun save(preview: AttachmentPreview): String = withContext(Dispatchers.Default) {
        val mimeType = preview.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val displayName = AttachmentDownloadNames.displayName(
            fileName = preview.fileName,
            mimeType = mimeType,
            fallbackMillis = com.glagolitsa.currentTimeMillis(),
        )
        when {
            mimeType.startsWith("image/") -> saveToPhotos(preview.bytes, displayName, isVideo = false)
            mimeType.startsWith("video/") -> saveToPhotos(preview.bytes, displayName, isVideo = true)
            else -> saveToDocuments(preview.bytes, displayName)
        }
    }

    private suspend fun saveToPhotos(bytes: ByteArray, displayName: String, isVideo: Boolean): String {
        ensurePhotoAddAccess()
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = if (isVideo) "mp4" else "jpg")
        val url = iosWriteTemporaryFile(bytes, "glagolitsa-export", extension)
        return try {
            suspendCoroutine { cont ->
                PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                    {
                        if (isVideo) {
                            PHAssetChangeRequest.creationRequestForAssetFromVideoAtFileURL(url)
                        } else {
                            PHAssetChangeRequest.creationRequestForAssetFromImageAtFileURL(url)
                        }
                    },
                    completionHandler = { success, error ->
                        if (success) {
                            cont.resume("photos://$displayName")
                        } else {
                            cont.resumeWithException(
                                IllegalStateException(error?.localizedDescription ?: "Не удалось сохранить медиа"),
                            )
                        }
                    },
                )
            }
        } finally {
            iosRemoveFile(url)
        }
    }

    private fun saveToDocuments(bytes: ByteArray, displayName: String): String {
        val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        val base = (paths.firstOrNull() as? String) ?: error("Documents directory unavailable")
        val dir = "$base/Glagolitsa"
        check(NSFileManager.defaultManager.createDirectoryAtPath(
            dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )) { "Не удалось создать папку для вложений" }
        val target = "$dir/$displayName"
        check(bytes.toNSData().writeToFile(target, atomically = true)) {
            "Не удалось сохранить вложение"
        }
        return target
    }

    private suspend fun ensurePhotoAddAccess() {
        val status = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelAddOnly)
        if (status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited) return
        val granted = suspendCancellableCoroutine { cont ->
            PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { next ->
                if (cont.isActive) {
                    cont.resume(
                        next == PHAuthorizationStatusAuthorized || next == PHAuthorizationStatusLimited,
                    )
                }
            }
        }
        if (!granted) error("Нет доступа к Фото")
    }
}
