// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileAboutPolicyTest {
    @Test
    fun normalize_trimsAndCapsLength() {
        assertEquals("на встрече", ProfileAboutPolicy.normalize("  на встрече  "))
        val long = "x".repeat(ProfileAboutPolicy.MAX_LENGTH + 20)
        assertEquals(ProfileAboutPolicy.MAX_LENGTH, ProfileAboutPolicy.normalize(long).length)
    }

    @Test
    fun hasChanges_comparesNormalizedValues() {
        assertFalse(ProfileAboutPolicy.hasChanges("  hi  ", "hi"))
        assertTrue(ProfileAboutPolicy.hasChanges("hi", "bye"))
    }

    @Test
    fun peerAboutLabel_usesOnlyBio() {
        assertEquals("bio", ProfileAboutPolicy.peerAboutLabel(" bio "))
        assertNull(ProfileAboutPolicy.peerAboutLabel(null))
        assertNull(ProfileAboutPolicy.peerAboutLabel(""))
        assertNull(ProfileAboutPolicy.peerAboutLabel("   "))
    }

    @Test
    fun peerAboutLabel_doesNotTreatBioAsRuntimePresence() {
        assertEquals("в сети", ProfileAboutPolicy.peerAboutLabel("в сети"))
        assertEquals("был(а) сегодня", ProfileAboutPolicy.peerAboutLabel("был(а) сегодня"))
        assertEquals("в звонке", ProfileAboutPolicy.peerAboutLabel("в звонке"))
        assertEquals("кофе", ProfileAboutPolicy.peerAboutLabel("кофе"))
    }

    @Test
    fun looksLikeRuntimeStatusLabel_detectsPresenceAndModeLabels() {
        assertTrue(ProfileAboutPolicy.looksLikeRuntimeStatusLabel("в сети"))
        assertTrue(ProfileAboutPolicy.looksLikeRuntimeStatusLabel("dnd"))
        assertTrue(ProfileAboutPolicy.looksLikeRuntimeStatusLabel("был вчера"))
        assertFalse(ProfileAboutPolicy.looksLikeRuntimeStatusLabel("на встрече"))
    }

    @Test
    fun profileUpdateForAbout_writesBioAndPreservesStatus() {
        val user = User(
            id = "u1",
            username = "alice",
            display_name = "Alice",
            position = "dev",
            status = "old status",
            bio = "old bio",
            avatar_url = "data:image/png;base64,xx",
            presence = "away",
        )

        val input = ProfileAboutPolicy.profileUpdateForAbout(user, "  новый текст  ")

        assertEquals("новый текст", input.bio)
        assertEquals("old status", input.status)
        assertEquals("Alice", input.displayName)
        assertEquals("dev", input.position)
        assertEquals("data:image/png;base64,xx", input.avatarUrl)
        assertEquals("away", input.presence)
    }

    @Test
    fun isTransientSyncFailure_matchesNetworkAndServerFailuresOnly() {
        assertTrue(ProfileAboutPolicy.isTransientSyncFailure("Failed to connect to /10.0.2.2"))
        assertTrue(ProfileAboutPolicy.isTransientSyncFailure("request timed out"))
        assertTrue(ProfileAboutPolicy.isTransientSyncFailure("server error", httpStatus = 503))
        assertFalse(ProfileAboutPolicy.isTransientSyncFailure("validation failed", httpStatus = 400))
        assertFalse(ProfileAboutPolicy.isTransientSyncFailure(null, httpStatus = 200))
    }
}
