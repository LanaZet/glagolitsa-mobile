// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals

class UserTest {
    private val baseUser = User(
        id = "u1",
        username = "test",
        display_name = null,
        nickname = "ignored",
    )

    @Test
    fun displayLabel_prefersDisplayName() {
        val user = baseUser.copy(display_name = "Тестовый")
        assertEquals("Тестовый", user.displayLabel())
    }

    @Test
    fun displayLabel_fallsBackToUsername() {
        assertEquals("test", baseUser.displayLabel())
    }

    @Test
    fun applyProfileEdits_updatesFieldsAndClearsBlanks() {
        val input = ProfileUpdateInput(
            displayName = "Имя",
            position = "",
            status = "",
            bio = "Пара слов о себе",
            avatarUrl = null,
            presence = "away",
        )
        val updated = baseUser.applyProfileEdits(input)

        assertEquals("Имя", updated.display_name)
        assertEquals(null, updated.position)
        assertEquals(null, updated.status)
        assertEquals("Пара слов о себе", updated.bio)
        assertEquals("away", updated.presence)
    }

    @Test
    fun profileUpdateInput_normalizedTrimsWhitespace() {
        val input = ProfileUpdateInput(
            displayName = "  Имя  ",
            position = "  dev ",
            status = "",
            bio = "",
            avatarUrl = null,
            presence = "online",
        ).normalized()

        assertEquals("Имя", input.displayName)
        assertEquals("dev", input.position)
    }
}
