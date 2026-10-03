// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountRecoveryKeyTest {
    @Test
    fun generate_is128BitsGroupedHex() {
        val key = AccountRecoveryKey.generate()
        val normalized = AccountRecoveryKey.normalize(key)
        assertEquals(AccountRecoveryKey.NORMALIZED_LENGTH, normalized.length)
        assertTrue(normalized.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(AccountRecoveryKey.format(normalized), key)
    }

    @Test
    fun normalize_stripsDashesAndCase() {
        assertEquals("abcdef0123456789abcdef0123456789", AccountRecoveryKey.normalize("ABCD-EF01-2345-6789-ABCD-EF01-2345-6789"))
    }

    @Test
    fun validate_rejectsShortKey() {
        assertEquals("Ключ восстановления слишком короткий", AccountRecoveryKey.validate("abcd"))
        assertNull(AccountRecoveryKey.validate("abcd-ef01-2345-6789-abcd-ef01-2345-6789"))
    }
}
