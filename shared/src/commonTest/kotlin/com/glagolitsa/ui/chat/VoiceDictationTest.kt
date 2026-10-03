// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceDictationTest {
    @Test
    fun appendVoiceDictationText_addsTranscriptToEmptyDraft() {
        assertEquals(
            "привет",
            appendVoiceDictationText("", " привет "),
        )
    }

    @Test
    fun appendVoiceDictationText_insertsSpaceWhenDraftHasText() {
        assertEquals(
            "первый второй",
            appendVoiceDictationText("первый", "второй"),
        )
    }

    @Test
    fun appendVoiceDictationText_keepsExistingTrailingSpace() {
        assertEquals(
            "первый второй",
            appendVoiceDictationText("первый ", "второй"),
        )
    }
}
