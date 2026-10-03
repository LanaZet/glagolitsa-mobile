// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import com.glagolitsa.platform.toByteArray
import com.glagolitsa.platform.toNSData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import platform.posix.pread

@OptIn(ExperimentalForeignApi::class)
actual class PlatformMediaFileStore actual constructor() : MediaFileStore {
    private val root: String by lazy {
        val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        val base = (paths.firstOrNull() as? String) ?: NSHomeDirectory()
        val dir = "$base/glagolitsa-media-cache"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        dir
    }

    actual override suspend fun writeEncrypted(cacheId: String, bytes: ByteArray): String =
        withContext(Dispatchers.Default) {
            val target = fileFor(cacheId)
            check(bytes.toNSData().writeToFile(target, atomically = true)) {
                "failed to write encrypted media"
            }
            target
        }

    actual override suspend fun copyEncrypted(sourcePath: String, cacheId: String): String =
        withContext(Dispatchers.Default) {
            require(isUnderRoot(sourcePath)) { "encrypted source file is missing" }
            val data = NSData.dataWithContentsOfFile(sourcePath)
                ?: error("encrypted source file is missing")
            val target = fileFor(cacheId)
            check(data.writeToFile(target, atomically = true)) {
                "failed to copy encrypted media"
            }
            target
        }

    actual override suspend fun readEncrypted(path: String): ByteArray? =
        withContext(Dispatchers.Default) {
            if (!isUnderRoot(path)) return@withContext null
            val data = NSData.dataWithContentsOfFile(path) ?: return@withContext null
            data.toByteArray()
        }

    actual override suspend fun readEncryptedChunk(path: String, offset: Long, maxBytes: Int): ByteArray? =
        withContext(Dispatchers.Default) {
            require(offset >= 0) { "offset must be >= 0" }
            require(maxBytes > 0) { "maxBytes must be > 0" }
            if (!isUnderRoot(path)) return@withContext null
            val descriptor = open(path, O_RDONLY)
            if (descriptor < 0) return@withContext null
            try {
                val buffer = ByteArray(maxBytes)
                val bytesRead = buffer.usePinned { pinned ->
                    pread(descriptor, pinned.addressOf(0), maxBytes.toULong(), offset)
                }
                when {
                    bytesRead < 0 -> null
                    bytesRead == 0L -> null
                    else -> buffer.copyOf(bytesRead.toInt())
                }
            } finally {
                close(descriptor)
            }
        }

    actual override suspend fun delete(path: String) = withContext(Dispatchers.Default) {
        if (isUnderRoot(path)) {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
        Unit
    }

    actual override suspend fun exists(path: String): Boolean = withContext(Dispatchers.Default) {
        isUnderRoot(path) && NSFileManager.defaultManager.fileExistsAtPath(path)
    }

    private fun fileFor(cacheId: String): String = "$root/${safeName(cacheId)}.bin"

    private fun isUnderRoot(path: String): Boolean = path.startsWith("$root/")

    private fun safeName(cacheId: String): String =
        cacheId.map { char -> if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_' }
            .joinToString("")
            .ifBlank { "attachment" }

}
