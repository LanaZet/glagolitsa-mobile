// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * In-memory LRU for open channel images (Telegram/Discord pattern:
 * never keep full-res bitmaps for gallery grids; cap concurrent network).
 */
object OpenMediaImageCache {
    private const val MAX_ENTRIES = 64
    private val mutex = Mutex()
    /** Manual LRU (LinkedHashMap.removeEldestEntry is JVM-only). */
    private val order = ArrayDeque<String>()
    private val map = HashMap<String, ImageBitmap>(32)

    fun cacheKey(fileId: String, maxEdgePx: Int): String = "$fileId@$maxEdgePx"

    suspend fun get(key: String): ImageBitmap? = mutex.withLock {
        val value = map[key] ?: return@withLock null
        order.remove(key)
        order.addLast(key)
        value
    }

    suspend fun put(key: String, bitmap: ImageBitmap) = mutex.withLock {
        if (map.containsKey(key)) {
            order.remove(key)
        }
        map[key] = bitmap
        order.addLast(key)
        while (order.size > MAX_ENTRIES) {
            val eldest = order.removeFirst()
            map.remove(eldest)
        }
    }

    suspend fun clear() = mutex.withLock {
        map.clear()
        order.clear()
    }
}

/**
 * Bounded loader with disk cache + memory LRU + concurrent download cap.
 *
 * Order: memory → disk → network (write disk) → decode → memory.
 */
class OpenMediaImageLoader(
    private val download: suspend (fileId: String) -> ByteArray,
    private val decode: (bytes: ByteArray, maxEdgePx: Int) -> ImageBitmap?,
    maxConcurrent: Int = 3,
    private val disk: PlatformOpenMediaDiskCache = PlatformOpenMediaDiskCache,
) {
    private val gate = Semaphore(maxConcurrent.coerceIn(1, 6))

    suspend fun load(fileId: String, maxEdgePx: Int): ImageBitmap? {
        if (fileId.isBlank()) return null
        val key = OpenMediaImageCache.cacheKey(fileId, maxEdgePx)
        OpenMediaImageCache.get(key)?.let { return it }

        // Disk bytes can decode without holding a network permit.
        val diskBytes = runCatching { disk.read(fileId) }.getOrNull()
        if (diskBytes != null && diskBytes.isNotEmpty()) {
            runCatching { disk.touch(fileId) }
            val bmp = decode(diskBytes, maxEdgePx) ?: return null
            OpenMediaImageCache.put(key, bmp)
            return bmp
        }

        return gate.withPermit {
            OpenMediaImageCache.get(key)?.let { return@withPermit it }
            // Re-check disk after waiting for permit (another loader may have filled it).
            val again = runCatching { disk.read(fileId) }.getOrNull()
            if (again != null && again.isNotEmpty()) {
                runCatching { disk.touch(fileId) }
                val bmp = decode(again, maxEdgePx) ?: return@withPermit null
                OpenMediaImageCache.put(key, bmp)
                return@withPermit bmp
            }
            val bytes = runCatching { download(fileId) }.getOrNull() ?: return@withPermit null
            if (bytes.isEmpty()) return@withPermit null
            runCatching { disk.write(fileId, bytes) }
            val bmp = decode(bytes, maxEdgePx) ?: return@withPermit null
            OpenMediaImageCache.put(key, bmp)
            bmp
        }
    }

    /** Warm cache for upcoming cells (neighbor prefetch). */
    suspend fun prefetch(fileId: String, maxEdgePx: Int) {
        if (fileId.isBlank()) return
        val key = OpenMediaImageCache.cacheKey(fileId, maxEdgePx)
        if (OpenMediaImageCache.get(key) != null) return
        if (runCatching { disk.exists(fileId) }.getOrDefault(false)) {
            // Decode into memory from disk without network.
            load(fileId, maxEdgePx)
            return
        }
        load(fileId, maxEdgePx)
    }

    suspend fun prefetchAll(fileIds: List<String>, maxEdgePx: Int) = coroutineScope {
        fileIds.distinct().filter { it.isNotBlank() }.forEach { id ->
            launch {
                runCatching { prefetch(id, maxEdgePx) }
            }
        }
    }
}

/** Gallery cell ~120–160dp @3x ≈ 480px; use 320 to match server thumb edge. */
const val OPEN_MEDIA_GALLERY_EDGE = 320

/** Feed full-width card. */
const val OPEN_MEDIA_FEED_EDGE = 720

/** Lightbox progressive full. */
const val OPEN_MEDIA_LIGHTBOX_EDGE = 1440
