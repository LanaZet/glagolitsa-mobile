// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatCreationPolicyTest {
    @Test
    fun title_requiresNonBlankWithinLimit() {
        assertFalse(ChatCreationPolicy.isTitleValid(""))
        assertFalse(ChatCreationPolicy.isTitleValid("   "))
        assertTrue(ChatCreationPolicy.isTitleValid("News"))
    }

    @Test
    fun publicChannel_requiresValidAvailableSlug() {
        assertFalse(
            ChatCreationPolicy.canCreateChannel(
                title = "News",
                visibility = ChatVisibility.PUBLIC,
                slug = "ab",
                slugAvailable = true,
            ),
        )
        assertFalse(
            ChatCreationPolicy.canCreateChannel(
                title = "News",
                visibility = ChatVisibility.PUBLIC,
                slug = "news_channel",
                slugAvailable = null,
            ),
        )
        assertFalse(
            ChatCreationPolicy.canCreateChannel(
                title = "News",
                visibility = ChatVisibility.PUBLIC,
                slug = "news_channel",
                slugAvailable = false,
            ),
        )
        assertTrue(
            ChatCreationPolicy.canCreateChannel(
                title = "News",
                visibility = ChatVisibility.PUBLIC,
                slug = "news_channel",
                slugAvailable = true,
            ),
        )
    }

    @Test
    fun privateChannel_doesNotRequireSlug() {
        assertTrue(
            ChatCreationPolicy.canCreateChannel(
                title = "Staff",
                visibility = ChatVisibility.PRIVATE,
                slug = "",
                slugAvailable = null,
            ),
        )
    }

    @Test
    fun normalizeSlug_lowercasesAndFilters() {
        assertEquals("news_1", ChatCreationPolicy.normalizeSlug(" News 1 "))
    }

    @Test
    fun suggestSlugFromTitle_fromLatinAndCyrillic() {
        assertEquals("glagolitsa_news", ChatCreationPolicy.suggestSlugFromTitle("Glagolitsa News"))
        assertEquals("novosti", ChatCreationPolicy.suggestSlugFromTitle("Новости"))
        assertEquals("", ChatCreationPolicy.suggestSlugFromTitle("   "))
        assertEquals("ab_ch", ChatCreationPolicy.suggestSlugFromTitle("ab"))
    }

    @Test
    fun suggestSlugAlternative_appendsCounter() {
        assertEquals("news_2", ChatCreationPolicy.suggestSlugAlternative("news", 0))
        assertEquals("news_3", ChatCreationPolicy.suggestSlugAlternative("news", 1))
    }
}
