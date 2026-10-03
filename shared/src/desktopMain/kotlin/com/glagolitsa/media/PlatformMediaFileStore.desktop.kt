// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual class PlatformMediaFileStore actual constructor() : MediaFileStore {
    private val root: File by lazy {
        File(System.getProperty("user.home"), ".glagolitsa/media-cache").apply { mkdirs() }
    }

    actual override suspend fun writeEncrypted(cacheId: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val target = fileFor(cacheId)
        target.parentFile?.mkdirs()
        val tmp = File.createTempFile(".upload-", ".tmp", target.parentFile)
        try {
            tmp.writeBytes(bytes)
            if (target.exists()) target.delete()
            check(tmp.renameTo(target)) { "Could not move media cache file into place" }
            target.absolutePath
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    actual override suspend fun copyEncrypted(sourcePath: String, cacheId: String): String = withContext(Dispatchers.IO) {
        val source = checkedFile(sourcePath)
        require(source?.isFile == true) { "encrypted source file is missing" }
        val target = fileFor(cacheId)
        target.parentFile?.mkdirs()
        val tmp = File.createTempFile(".upload-", ".tmp", target.parentFile)
        try {
            source.inputStream().use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (target.exists()) target.delete()
            check(tmp.renameTo(target)) { "Could not move media cache file into place" }
            target.absolutePath
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    actual override suspend fun readEncrypted(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = checkedFile(path)
        if (file?.isFile == true) file.readBytes() else null
    }

    actual override suspend fun readEncryptedChunk(path: String, offset: Long, maxBytes: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            require(offset >= 0) { "offset must be >= 0" }
            require(maxBytes > 0) { "maxBytes must be > 0" }
            val file = checkedFile(path)
            if (file?.isFile != true || offset >= file.length()) return@withContext null
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val targetSize = minOf(maxBytes.toLong(), file.length() - offset).toInt()
                val buffer = ByteArray(targetSize)
                val read = raf.read(buffer)
                if (read <= 0) null else buffer.copyOf(read)
            }
        }

    actual override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        checkedFile(path)?.takeIf { it.exists() }?.delete()
        Unit
    }

    actual override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        checkedFile(path)?.isFile == true
    }

    private fun fileFor(cacheId: String): File = File(root, "${safeName(cacheId)}.bin")

    private fun checkedFile(path: String): File? {
        val file = File(path)
        val rootPath = root.canonicalPath
        val filePath = runCatching { file.canonicalPath }.getOrNull() ?: return null
        return file.takeIf { filePath.startsWith(rootPath + File.separator) }
    }

    private fun safeName(cacheId: String): String =
        cacheId.map { char -> if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_' }
            .joinToString("")
            .ifBlank { "attachment" }
}
