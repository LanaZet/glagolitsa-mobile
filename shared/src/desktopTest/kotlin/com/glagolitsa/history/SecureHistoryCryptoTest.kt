// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.history

import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import com.glagolitsa.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureHistoryCryptoTest {
    @Test
    fun recoveryKey_isHighEntropyHex() {
        val key = SecureHistoryCrypto.generateRecoveryKey()
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun sealOpen_roundTrip_withRecoveryKeyOnly() {
        val archive = AccountHistoryArchive(
            user_id = "user-1",
            exported_at = "2026-07-19T00:00:00Z",
            chats = listOf(Chat(id = "c1", title = "dm", type = ChatType.DIRECT)),
            messages = listOf(
                Message(id = "m1", chat_id = "c1", sender_id = "user-1", body = "secret-hello"),
            ),
        )
        val recoveryKey = SecureHistoryCrypto.generateRecoveryKey()
        val sealed = SecureHistoryCrypto.seal(recoveryKey, archive.encodeToBytes())
        assertTrue(SecureHistoryCrypto.isSecureBackupBlob(sealed))
        assertFalse(sealed.copyOfRange(0, 6).contentEquals("GLHIST2".encodeToByteArray()))

        val plain = SecureHistoryCrypto.open(recoveryKey, sealed)
        assertNotNull(plain)
        val restored = decodeAccountHistoryArchive(plain)
        assertEquals("secret-hello", restored.messages.single().body)

        // Login password style secret must NOT open the archive.
        assertNull(SecureHistoryCrypto.open("wrong-passphrase", sealed))
        assertNull(SecureHistoryCrypto.open("wrong-key-000000000000000000000000000000000000000000000000000000000000", sealed))
    }

    @Test
    fun passwordIsNotUsedAsUnlock_documentedInvariant() {
        // Explicit regression: GLHIST2 password-path was removed. Magic must be GLSBR1.
        val key = SecureHistoryCrypto.generateRecoveryKey()
        val sealed = SecureHistoryCrypto.seal(key, "hi".encodeToByteArray())
        assertTrue(sealed.copyOfRange(0, 6).decodeToString() == "GLSBR1")
    }
}
