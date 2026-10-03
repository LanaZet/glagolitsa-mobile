// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import kotlinx.cinterop.ExperimentalForeignApi
import com.glagolitsa.platform.toByteArray
import com.glagolitsa.platform.toNSData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
actual object PlatformOpenMediaDiskCache {
    private val root: String by lazy {
        val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        val base = (paths.firstOrNull() as? String) ?: NSHomeDirectory()
        val dir = "$base/glagolitsa-open-media"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        dir
    }

    private fun fileFor(fileId: String): String {
        val safe = fileId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "$root/$safe.bin"
    }

    actual suspend fun read(fileId: String): ByteArray? = withContext(Dispatchers.Default) {
        val path = fileFor(fileId)
        val data = NSData.dataWithContentsOfFile(path) ?: return@withContext null
        touchPath(path)
        data.toByteArray()
    }

    actual suspend fun write(fileId: String, bytes: ByteArray) = withContext(Dispatchers.Default) {
        if (fileId.isBlank() || bytes.isEmpty()) return@withContext
        val path = fileFor(fileId)
        check(bytes.toNSData().writeToFile(path, atomically = true)) {
            "failed to write open media cache"
        }
        runEvictionLocked()
    }

    actual suspend fun exists(fileId: String): Boolean = withContext(Dispatchers.Default) {
        NSFileManager.defaultManager.fileExistsAtPath(fileFor(fileId))
    }

    actual suspend fun delete(fileId: String) = withContext(Dispatchers.Default) {
        NSFileManager.defaultManager.removeItemAtPath(fileFor(fileId), error = null)
        Unit
    }

    actual suspend fun touch(fileId: String) = withContext(Dispatchers.Default) {
        touchPath(fileFor(fileId))
    }

    actual suspend fun listForEviction(): List<OpenMediaDiskEntry> = withContext(Dispatchers.Default) {
        listEntriesLocked()
    }

    actual suspend fun runEviction() = withContext(Dispatchers.Default) {
        runEvictionLocked()
    }

    private fun touchPath(path: String) {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return
        val now = NSDate()
        NSFileManager.defaultManager.setAttributes(
            mapOf(
                platform.Foundation.NSFileModificationDate to now,
            ),
            ofItemAtPath = path,
            error = null,
        )
    }

    private fun listEntriesLocked(): List<OpenMediaDiskEntry> {
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(root, error = null)
            ?: return emptyList()
        return names.mapNotNull { anyName ->
            val name = anyName as? String ?: return@mapNotNull null
            if (!name.endsWith(".bin")) return@mapNotNull null
            val path = "$root/$name"
            val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
            val size = (attrs?.get(platform.Foundation.NSFileSize) as? Number)?.toLong() ?: 0L
            val mod = attrs?.get(platform.Foundation.NSFileModificationDate) as? NSDate
            val ms = ((mod?.timeIntervalSince1970 ?: 0.0) * 1000.0).toLong()
            OpenMediaDiskEntry(
                fileId = name.removeSuffix(".bin"),
                sizeBytes = size,
                lastAccessMs = ms,
            )
        }.sortedBy { it.lastAccessMs }
    }

    private fun runEvictionLocked() {
        val entries = listEntriesLocked()
        val toDelete = OpenMediaDiskBudget.idsToEvict(
            entries.map { Triple(it.fileId, it.sizeBytes, it.lastAccessMs) },
        )
        for (id in toDelete) {
            NSFileManager.defaultManager.removeItemAtPath(fileFor(id), error = null)
        }
    }

}
