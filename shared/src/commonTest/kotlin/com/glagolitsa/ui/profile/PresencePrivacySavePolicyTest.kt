// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import com.glagolitsa.model.PresencePrivacySettings
import com.glagolitsa.model.PresenceVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresencePrivacySavePolicyTest {
    @Test
    fun isSameNetworkPrivacy_comparesOnlyVisibilityFields() {
        val hidden = PresencePrivacySettings(
            online_visibility = PresenceVisibility.NOBODY,
            last_seen_visibility = PresenceVisibility.NOBODY,
        )
        val visible = PresencePrivacySettings(
            online_visibility = PresenceVisibility.CONTACTS,
            last_seen_visibility = PresenceVisibility.CONTACTS,
        )

        assertTrue(isSameNetworkPrivacy(hidden, hidden.copy()))
        assertFalse(isSameNetworkPrivacy(hidden, visible))
    }

    @Test
    fun presencePrivacyUserMessage_hidesRawTimeoutAndUrl() {
        val message = presencePrivacyUserMessage(
            IllegalStateException("request_timeout [url=https://api.example.test/presence/privacy]"),
        )

        assertEquals("Сервер не ответил вовремя. Попробуйте ещё раз.", message)
    }
}
