// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlagolitsaInviteLinkTest {
    @Test
    fun parseChannelSlugUrl() {
        val target = GlagolitsaInviteLink.parse("https://glagolitsa.app/c/zcoi")
        val slug = assertIs<GlagolitsaInviteTarget.ChannelSlug>(target)
        assertEquals("zcoi", slug.slug)
    }

    @Test
    fun parseBareHostAndAtSlug() {
        val host = assertIs<GlagolitsaInviteTarget.ChannelSlug>(
            GlagolitsaInviteLink.parse("glagolitsa.app/c/zcoi"),
        )
        assertEquals("zcoi", host.slug)
        val at = assertIs<GlagolitsaInviteTarget.ChannelSlug>(
            GlagolitsaInviteLink.parse("@zcoi"),
        )
        assertEquals("zcoi", at.slug)
    }

    @Test
    fun parseCallJoinUrl() {
        val target = GlagolitsaInviteLink.parse("https://glagolitsa.app/call/aabbccdd-eeff-0011-2233-445566778899")
        val call = assertIs<GlagolitsaInviteTarget.CallId>(target)
        assertEquals("aabbccdd-eeff-0011-2233-445566778899", call.callId)
        assertEquals(
            "https://glagolitsa.app/call/aabbccdd-eeff-0011-2233-445566778899",
            callJoinLink(call.callId),
        )
    }

    @Test
    fun callJoinLinkRejectsUnsafePathSegments() {
        assertFailsWith<IllegalArgumentException> {
            callJoinLink("abc/defghi")
        }
    }

    @Test
    fun parseJoinTokenUrl() {
        val target = GlagolitsaInviteLink.parse("https://glagolitsa.app/join/aabbccddeeff0011")
        val token = assertIs<GlagolitsaInviteTarget.JoinToken>(target)
        assertEquals("aabbccddeeff0011", token.token)
    }

    @Test
    fun findInMessageBody() {
        val text = "зайди https://glagolitsa.app/c/zcoi пожалуйста"
        val spans = GlagolitsaInviteLink.findInText(text)
        assertEquals(1, spans.size)
        assertEquals("https://glagolitsa.app/c/zcoi", spans[0].raw)
        assertTrue(text.substring(spans[0].start, spans[0].endExclusive) == spans[0].raw)
        val hit = GlagolitsaInviteLink.spanAt(text, spans[0].start + 3)
        assertEquals(spans[0], hit)
        assertNull(GlagolitsaInviteLink.spanAt(text, 0))
    }
}
