// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareIntakePolicyTest {

    @Test
    fun normalize_trims_and_rejects_blank() {
        assertNull(ShareIntakePolicy.normalizeSharedText(null))
        assertNull(ShareIntakePolicy.normalizeSharedText("   "))
        assertEquals("hi", ShareIntakePolicy.normalizeSharedText("  hi  "))
    }

    @Test
    fun normalize_caps_length() {
        val long = "x".repeat(ShareIntakePolicy.MAX_SHARED_TEXT_LENGTH + 50)
        val n = ShareIntakePolicy.normalizeSharedText(long)!!
        assertEquals(ShareIntakePolicy.MAX_SHARED_TEXT_LENGTH, n.length)
    }

    @Test
    fun primaryUrl_extracts_first_http() {
        assertEquals(
            "https://youtu.be/abc",
            ShareIntakePolicy.primaryUrl("Смотри https://youtu.be/abc и ещё"),
        )
        assertEquals(
            "https://t.me/foo",
            ShareIntakePolicy.primaryUrl("https://t.me/foo."),
        )
        assertNull(ShareIntakePolicy.primaryUrl("просто текст"))
    }

    @Test
    fun orderShareTargets_channels_first() {
        val dm = Chat(id = "d", title = "Alice", type = ChatType.DIRECT)
        val group = Chat(id = "g", title = "Dev", type = ChatType.GROUP)
        val channel = Chat(
            id = "c",
            title = "News",
            type = ChatType.CHANNEL,
            visibility = ChatVisibility.PRIVATE,
        )
        val ordered = ShareIntakePolicy.orderShareTargets(listOf(dm, channel, group))
        assertEquals(listOf("c", "g", "d"), ordered.map { it.id })
    }

    @Test
    fun kindLabel_channel_public_slug() {
        val chat = Chat(
            id = "c",
            title = "News",
            type = ChatType.CHANNEL,
            visibility = ChatVisibility.PUBLIC,
            slug = "glag_news",
        )
        assertEquals("@glag_news", ShareIntakePolicy.kindLabel(chat))
        assertTrue(ShareIntakePolicy.kindLabel(chat.copy(slug = null)).contains("Публичный"))
    }
}
