// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Network presence, profile about text, manual presence, and message delivery
 * statuses must stay separate channels.
 */
class StatusChannelsTest {

    private val user = User(
        id = "u1",
        username = "alice",
        display_name = "Alice",
        status = "legacy about",
        bio = "bio text",
        presence = "away",
        avatar_url = "data:image/png;base64,xx",
        position = "dev",
    )

    @Test
    fun privacy_defaultContacts_isNetworkVisible() {
        assertTrue(StatusChannels.isNetworkStatusVisible(PresencePrivacySettings()))
        assertTrue(PresencePrivacySettings().isNetworkVisible())
    }

    @Test
    fun privacy_nobody_hidesNetworkStatus() {
        val hidden = StatusChannels.privacyForNetworkVisible(false)
        assertFalse(StatusChannels.isNetworkStatusVisible(hidden))
        assertEquals(PresenceVisibility.NOBODY, hidden.online_visibility)
        assertEquals(PresenceVisibility.NOBODY, hidden.last_seen_visibility)
    }

    @Test
    fun privacy_contacts_showsNetworkStatus() {
        val shown = StatusChannels.privacyForNetworkVisible(true)
        assertTrue(StatusChannels.isNetworkStatusVisible(shown))
        assertEquals(PresenceVisibility.CONTACTS, shown.online_visibility)
        assertEquals(PresenceVisibility.CONTACTS, shown.last_seen_visibility)
    }

    @Test
    fun privacy_partialNobody_stillVisibleIfOtherChannelOpen() {
        val onlineOnlyHidden = PresencePrivacySettings(
            online_visibility = PresenceVisibility.NOBODY,
            last_seen_visibility = PresenceVisibility.CONTACTS,
        )
        assertTrue(StatusChannels.isNetworkStatusVisible(onlineOnlyHidden))
    }

    @Test
    fun updatePrivacyRequest_onlyTouchesVisibilityFields() {
        val req = StatusChannels.updatePrivacyRequest(false)
        assertEquals(PresenceVisibility.NOBODY, req.online_visibility)
        assertEquals(PresenceVisibility.NOBODY, req.last_seen_visibility)
    }

    @Test
    fun profileNetworkVisibilityCaption_neverEmbedsAboutOrModeLabel() {
        val on = StatusChannels.profileNetworkVisibilityCaption(true)
        val off = StatusChannels.profileNetworkVisibilityCaption(false)
        assertEquals("Сетевой статус виден контактам", on)
        assertEquals("Сетевой статус скрыт от других", off)
        assertFalse(on.contains("Скрыт"))
        assertFalse(on.contains(user.bio.orEmpty()))
        assertFalse(off.contains(user.status.orEmpty()))
        assertFalse(off.contains("Отошёл"))
        assertFalse(off.contains("Невидимый"))
    }

    @Test
    fun networkPresenceLabel_onlineAndOffline() {
        assertEquals(
            "в сети",
            StatusChannels.networkPresenceLabel(
                UserPresenceView(user_id = "u1", status = PresenceStatus.ONLINE),
            ),
        )
        assertEquals(
            "не в сети",
            StatusChannels.networkPresenceLabel(
                UserPresenceView(user_id = "u1", status = PresenceStatus.OFFLINE),
            ),
        )
        assertEquals("не в сети", StatusChannels.networkPresenceLabel(null))
    }

    @Test
    fun networkPresenceLabel_hiddenDoesNotLeakCallState() {
        val hidden = UserPresenceView(
            user_id = "u1",
            status = PresenceStatus.HIDDEN,
            in_call = true,
            call_id = "c1",
            last_seen_bucket = LastSeenBucket.RECENTLY,
        )
        assertEquals("был(а) недавно", StatusChannels.networkPresenceLabel(hidden))
        assertFalse(hidden.isOnlineLike())
    }

    @Test
    fun networkPresenceLabel_neverUsesProfileAboutText() {
        val online = UserPresenceView(user_id = "u1", status = PresenceStatus.ONLINE)
        val network = StatusChannels.networkPresenceLabel(online)
        val about = StatusChannels.profileAboutLabel("на встрече")
        assertEquals("в сети", network)
        assertEquals("на встрече", about)
        assertNotEquals(network, about)
    }

    @Test
    fun networkPresenceLabelOrUnknown_placeholderIsNetworkNotAbout() {
        assertEquals("нет данных о сети", StatusChannels.networkPresenceLabelOrUnknown(null))
        assertEquals("нет данных о сети", StatusChannels.networkPresenceLabelOrUnknown("  "))
        assertEquals("в сети", StatusChannels.networkPresenceLabelOrUnknown("в сети"))
    }

    @Test
    fun profileStatusLabel_usesOnlyStatus() {
        assertEquals("жиза", StatusChannels.profileStatusLabel(" жиза "))
        assertNull(StatusChannels.profileStatusLabel(null))
        assertNull(StatusChannels.profileStatusLabel("  "))
    }

    @Test
    fun profileAboutLabel_usesOnlyBio() {
        assertEquals("bio", StatusChannels.profileAboutLabel(" bio "))
        assertNull(StatusChannels.profileAboutLabel(null))
        assertNull(StatusChannels.profileAboutLabel("  "))
    }

    @Test
    fun profileAboutLabelOrPlaceholder_whenEmpty() {
        assertEquals("Не указано", StatusChannels.profileAboutLabelOrPlaceholder(null))
        assertEquals("ок", StatusChannels.profileAboutLabelOrPlaceholder("ок"))
    }

    @Test
    fun profileTextLines_keepStatusAndAboutIndependent() {
        assertTrue(StatusChannels.looksLikeNetworkPresenceLabel("в сети"))
        assertTrue(StatusChannels.looksLikeNetworkPresenceLabel("был(а) сегодня"))
        assertTrue(StatusChannels.looksLikeNetworkPresenceLabel("в звонке"))
        assertFalse(StatusChannels.looksLikeNetworkPresenceLabel("на встрече"))
        assertFalse(StatusChannels.looksLikeNetworkPresenceLabel("кофе"))
        val lines = StatusChannels.profileTextLines(status = "жиза", bio = null)
        assertEquals("жиза", lines.status)
        assertNull(lines.about)
    }

    @Test
    fun chatInfoStatusLines_keepsAboutAndNetworkIndependent() {
        val lines = StatusChannels.chatInfoStatusLines(
            status = "жиза",
            about = "на встрече",
            networkPresence = UserPresenceView(user_id = "u2", status = PresenceStatus.ONLINE),
        )
        assertEquals("жиза", lines.status)
        assertEquals("на встрече", lines.about)
        assertEquals("в сети", lines.networkPresence)
    }

    @Test
    fun chatInfoStatusLines_emptyAboutDoesNotCopyNetwork() {
        val lines = StatusChannels.chatInfoStatusLines(
            status = "жиза",
            about = null,
            networkPresence = UserPresenceView(user_id = "u2", status = PresenceStatus.ONLINE),
        )
        assertEquals("жиза", lines.status)
        assertNull(lines.about)
        assertEquals("в сети", lines.networkPresence)
    }

    @Test
    fun chatInfoStatusLines_offlineNetworkDoesNotClearAbout() {
        val lines = StatusChannels.chatInfoStatusLines(
            status = "жиза",
            about = "работаю удалённо",
            networkPresence = UserPresenceView(user_id = "u2", status = PresenceStatus.OFFLINE),
        )
        assertEquals("жиза", lines.status)
        assertEquals("работаю удалённо", lines.about)
        assertEquals("не в сети", lines.networkPresence)
    }

    @Test
    fun chatInfoStatusLines_preformattedNetworkOverridesView() {
        val lines = StatusChannels.chatInfoStatusLines(
            status = "жиза",
            about = "кофе",
            networkPresence = UserPresenceView(user_id = "u2", status = PresenceStatus.OFFLINE),
            preformattedNetwork = "в звонке",
        )
        assertEquals("жиза", lines.status)
        assertEquals("кофе", lines.about)
        assertEquals("в звонке", lines.networkPresence)
    }

    @Test
    fun profileUpdateForAbout_changesOnlyBioAndPreservesStatus() {
        val input = StatusChannels.profileUpdateForAbout(user, "  новый текст  ")
        assertEquals("новый текст", input.bio)
        assertEquals("legacy about", input.status)
        assertEquals("Alice", input.displayName)
        assertEquals("dev", input.position)
        assertEquals("data:image/png;base64,xx", input.avatarUrl)
        assertEquals("away", input.presence)
        assertEquals(UserPresence.Away, UserPresence.fromApi(input.presence))
    }

    @Test
    fun profileUpdateForAbout_clearingAboutDoesNotTouchPresenceMode() {
        val input = StatusChannels.profileUpdateForAbout(user, "   ")
        assertEquals("", input.bio)
        assertEquals("legacy about", input.status)
        assertEquals("away", input.presence)
        val applied = user.applyProfileEdits(input.normalized())
        assertNull(applied.bio)
        assertEquals("legacy about", applied.status)
        assertEquals("away", applied.presence)
    }

    @Test
    fun applyProfileEdits_aboutStatusAndPresenceRemainSeparateFields() {
        val input = ProfileUpdateInput(
            displayName = "Alice",
            position = "dev",
            status = "",
            bio = "на обеде",
            avatarUrl = null,
            presence = "dnd",
        )
        val updated = user.applyProfileEdits(input)
        assertNull(updated.status)
        assertEquals("на обеде", updated.bio)
        assertEquals("dnd", updated.presence)
        assertEquals(UserPresence.Dnd, updated.presenceState())
        assertNotEquals(
            StatusChannels.networkPresenceLabel(
                UserPresenceView(user_id = updated.id, status = PresenceStatus.ONLINE),
            ),
            updated.bio,
        )
    }

    @Test
    fun manualPresenceMode_offlineIsInvisibleNotPrivacyCaption() {
        assertEquals("Невидимый", UserPresence.Offline.label)
        assertEquals("offline", UserPresence.Offline.apiValue)
        assertNotEquals(
            StatusChannels.profileNetworkVisibilityCaption(false),
            UserPresence.Offline.label,
        )
    }

    @Test
    fun userPresenceFromApi_defaultsOnline() {
        assertEquals(UserPresence.Online, UserPresence.fromApi(null))
        assertEquals(UserPresence.Away, UserPresence.fromApi("AWAY"))
    }

    @Test
    fun privacyToggle_doesNotMutateProfileAboutOnUser() {
        val beforeStatus = user.status
        val beforeBio = user.bio
        val beforePresence = user.presence
        StatusChannels.privacyForNetworkVisible(false)
        StatusChannels.updatePrivacyRequest(true)
        assertEquals(beforeStatus, user.status)
        assertEquals(beforeBio, user.bio)
        assertEquals(beforePresence, user.presence)
    }

    @Test
    fun profileAboutUpdate_doesNotProduceMessageDeliveryStatus() {
        val input = StatusChannels.profileUpdateForAbout(user, "кофе")
        assertFalse(MessageStatus.isKnownDeliveryStatus(input.bio))
        assertEquals("кофе", input.bio)
        assertEquals("legacy about", input.status)
    }
}
