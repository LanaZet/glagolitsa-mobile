// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.ui.graphics.ImageBitmap

/** Small LRU so fling reuse does not re-decode. Manual eviction: removeEldestEntry is JVM-only. */
object ChatImageBitmapCache {
    private val map = LinkedHashMap<String, ImageBitmap>(CHAT_IMAGE_CACHE_SIZE)

    fun get(id: String): ImageBitmap? {
        val bitmap = map.remove(id) ?: return null
        map[id] = bitmap
        return bitmap
    }

    fun put(id: String, bitmap: ImageBitmap) {
        if (id.isBlank()) return
        map.remove(id)
        map[id] = bitmap
        while (map.size > CHAT_IMAGE_CACHE_SIZE) {
            map.remove(map.keys.first())
        }
    }
}
