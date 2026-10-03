// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import org.junit.Assert.assertTrue
import org.junit.Test

class LibSignalGroupSelfTestTest {
    @Test
    fun senderKeyRoundtrip() {
        assertTrue(LibSignalGroupSelfTest.run())
    }
}