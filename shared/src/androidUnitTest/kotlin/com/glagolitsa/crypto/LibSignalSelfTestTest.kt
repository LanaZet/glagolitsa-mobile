// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import org.junit.Assert.assertTrue
import org.junit.Test

class LibSignalSelfTestTest {
    @Test
    fun encryptDecryptRoundtrip_withoutNetwork() {
        assertTrue(LibSignalSelfTest.basicRoundtrip())
    }

    @Test
    fun bidirectionalExchange_advancesRatchetBothWays() {
        assertTrue(LibSignalSelfTest.bidirectionalExchange())
    }

    @Test
    fun outOfOrderDecrypt_recoversAllMessages() {
        assertTrue(LibSignalSelfTest.outOfOrderDecrypt(count = 100))
    }

    @Test
    fun messageKeyLimit_rejectsVeryOldCiphertext() {
        assertTrue(LibSignalSelfTest.messageKeyLimitDropsOldMessages())
    }

    @Test
    fun deleteSession_blocksDecryptUntilRebuilt() {
        assertTrue(LibSignalSelfTest.deleteSessionBlocksFurtherDecryptUntilRebuilt())
    }

    @Test
    fun resetSession_allowsFreshEstablish() {
        assertTrue(LibSignalSelfTest.resetSessionAllowsFreshEstablish())
    }

    @Test
    fun run_allScenariosPass() {
        assertTrue(LibSignalSelfTest.run())
    }
}
