// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegistrationValidationTest {
    @Test
    fun isUsableUsername_acceptsOnlyEnglishLetters() {
        assertTrue(RegistrationValidation.isUsernameUsable("a"))
        assertTrue(RegistrationValidation.isUsernameUsable("Alice"))
        assertTrue(RegistrationValidation.isUsernameUsable("tatyana"))

        assertFalse(RegistrationValidation.isUsernameUsable("alice2"))
        assertFalse(RegistrationValidation.isUsernameUsable("alice.smith"))
        assertFalse(RegistrationValidation.isUsernameUsable("alice-smith"))
        assertFalse(RegistrationValidation.isUsernameUsable("alice_smith"))
        assertFalse(RegistrationValidation.isUsernameUsable("Татьяна"))
    }

    @Test
    fun normalizeUsername_lowercases() {
        assertEquals("alice", RegistrationValidation.normalizeUsername("  Alice "))
    }

    @Test
    fun sanitizeRegistrationUsernameInput_keepsOnlyEnglishLetters() {
        assertEquals("Ta", RegistrationValidation.sanitizeRegistrationUsernameInput(" Taтьяна-123 "))
    }

    @Test
    fun validate_rejectsDevSeedUsernames() {
        for (username in listOf("Marco", "polo")) {
            val errors = RegistrationValidation.validate(
                RegistrationValidation.Form(
                    username = username,
                    password = "password123",
                    passwordConfirm = "password123",
                ),
            )
            assertEquals(
                "Это служебный тестовый аккаунт — выберите другое имя",
                errors[RegistrationValidation.Field.USERNAME],
                "username=$username",
            )
        }
    }

    @Test
    fun validateForm_passwordTooShort() {
        val errors = RegistrationValidation.validate(
            RegistrationValidation.Form(
                username = "alice",
                password = "short",
                passwordConfirm = "short",
            ),
        )
        assertEquals("Пароль не короче 8 символов", errors[RegistrationValidation.Field.PASSWORD])
    }

    @Test
    fun validateForm_passwordMismatch() {
        val errors = RegistrationValidation.validate(
            RegistrationValidation.Form(
                username = "alice",
                password = "password123",
                passwordConfirm = "password124",
            ),
        )
        assertEquals("Пароли не совпадают", errors[RegistrationValidation.Field.PASSWORD_CONFIRM])
    }

    @Test
    fun validateForm_validMinimal() {
        val errors = RegistrationValidation.validate(
            RegistrationValidation.Form(
                username = "alice",
                password = "password123",
                passwordConfirm = "password123",
            ),
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun validateForm_optionalEmailInvalid() {
        val errors = RegistrationValidation.validate(
            RegistrationValidation.Form(
                username = "alice",
                email = "not-an-email",
                password = "password123",
                passwordConfirm = "password123",
            ),
        )
        assertEquals("Некорректный email", errors[RegistrationValidation.Field.EMAIL])
    }
}
