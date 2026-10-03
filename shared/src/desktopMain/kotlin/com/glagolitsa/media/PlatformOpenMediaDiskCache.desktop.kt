// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual object PlatformOpenMediaDiskCache {
    private val root: File by lazy {
        File(System.getProperty("user.home"), ".glagolitsa/open-media").apply { mkdirs() }
    }

    private fun fileFor(fileId: String): File {
        val safe = fileId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(root, "$safe.bin")
    }

    actual suspend fun read(fileId: String): ByteArray? = withContext(Dispatchers.IO) {
        val f = fileFor(fileId)
        if (!f.isFile) return@withContext null
        f.setLastModified(System.currentTimeMillis())
        f.readBytes()
    }

    actual suspend fun write(fileId: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (fileId.isBlank() || bytes.isEmpty()) return@withContext
        val target = fileFor(fileId)
        target.parentFile?.mkdirs()
        val tmp = File.createTempFile(".om-", ".tmp", target.parentFile)
        try {
            tmp.writeBytes(bytes)
            if (target.exists()) target.delete()
            check(tmp.renameTo(target)) { "open media disk cache rename failed" }
            target.setLastModified(System.currentTimeMillis())
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        runEvictionLocked()
    }

    actual suspend fun exists(fileId: String): Boolean = withContext(Dispatchers.IO) {
        fileFor(fileId).isFile
    }

    actual suspend fun delete(fileId: String) = withContext(Dispatchers.IO) {
        fileFor(fileId).delete()
        Unit
    }

    actual suspend fun touch(fileId: String) = withContext(Dispatchers.IO) {
        val f = fileFor(fileId)
        if (f.isFile) f.setLastModified(System.currentTimeMillis())
        Unit
    }

    actual suspend fun listForEviction(): List<OpenMediaDiskEntry> = withContext(Dispatchers.IO) {
        listEntriesLocked()
    }

    actual suspend fun runEviction() = withContext(Dispatchers.IO) {
        runEvictionLocked()
    }

    private fun listEntriesLocked(): List<OpenMediaDiskEntry> {
        val files = root.listFiles { f -> f.isFile && f.name.endsWith(".bin") } ?: return emptyList()
        return files.map { f ->
            val id = f.name.removeSuffix(".bin")
            OpenMediaDiskEntry(
                fileId = id,
                sizeBytes = f.length(),
                lastAccessMs = f.lastModified(),
            )
        }.sortedBy { it.lastAccessMs }
    }

    private fun runEvictionLocked() {
        val entries = listEntriesLocked()
        val toDelete = OpenMediaDiskBudget.idsToEvict(
            entries.map { Triple(it.fileId, it.sizeBytes, it.lastAccessMs) },
        )
        for (id in toDelete) {
            File(root, "$id.bin").delete()
        }
    }
}
