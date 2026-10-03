// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatMediaCachePolicyTest {
    @Test
    fun fromStorageKeyFallsBackToDefault() {
        assertEquals(ChatMediaKeepMode.Default, ChatMediaKeepMode.fromStorageKey(""))
        assertEquals(ChatMediaKeepMode.Default, ChatMediaKeepMode.fromStorageKey("unknown"))
        assertEquals(ChatMediaKeepMode.SevenDays, ChatMediaKeepMode.fromStorageKey("7d"))
    }

    @Test
    fun foreverPolicyExcludesChatFromTrim() {
        val policy = ChatMediaCachePolicy(ChatMediaKeepMode.Forever).retentionPolicyForChat("chat-1")

        assertTrue("chat-1" in policy.excludedChatIds)
        assertNull(policy.maxAgeMs)
    }

    @Test
    fun agePolicyKeepsScopeForSingleChatTrim() {
        val policy = ChatMediaCachePolicy(ChatMediaKeepMode.ThirtyDays).retentionPolicyForChat("chat-1")

        assertEquals(30L * 24L * 60L * 60L * 1000L, policy.maxAgeMs)
        assertTrue(policy.excludedChatIds.isEmpty())
        assertEquals(Long.MAX_VALUE, policy.maxBytes)
    }
}
