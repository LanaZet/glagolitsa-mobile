// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.auth

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RegistrationPowTest {
    @Test
    fun solve_findsSolutionForLowDifficulty() {
        val solution = RegistrationPow.solve("test-challenge", difficulty = 8)
        assertNotNull(solution)
        assertTrue(RegistrationPow.verify("test-challenge", solution, 8))
    }
}