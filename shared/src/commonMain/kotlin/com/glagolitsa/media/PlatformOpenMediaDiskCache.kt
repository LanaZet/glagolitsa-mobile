// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

data class OpenMediaDiskEntry(
    val fileId: String,
    val sizeBytes: Long,
    val lastAccessMs: Long,
)

/**
 * Disk cache for open channel media bytes (separate from e2e encrypted spool).
 * Keyed by server file_id. Telegram-style: survive process death, keep thumbs warm.
 */
expect object PlatformOpenMediaDiskCache {
    suspend fun read(fileId: String): ByteArray?
    suspend fun write(fileId: String, bytes: ByteArray)
    suspend fun exists(fileId: String): Boolean
    suspend fun delete(fileId: String)
    /** Bump last-access time for LRU eviction. */
    suspend fun touch(fileId: String)
    /** Oldest access first. */
    suspend fun listForEviction(): List<OpenMediaDiskEntry>
    /** Apply [OpenMediaDiskBudget] — delete oldest until under limits. */
    suspend fun runEviction()
}
