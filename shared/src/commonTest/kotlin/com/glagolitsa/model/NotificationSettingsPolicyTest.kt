// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationSettingsPolicyTest {

    @Test
    fun messageNotification_hiddenWhenDisabledOrEmpty() {
        assertFalse(
            NotificationSettingsPolicy.shouldShowMessageNotification(
                messagesEnabled = false,
                appliedInbound = 3,
                appInForeground = false,
                hasOpenChat = false,
            ),
        )
        assertFalse(
            NotificationSettingsPolicy.shouldShowMessageNotification(
                messagesEnabled = true,
                appliedInbound = 0,
                appInForeground = false,
                hasOpenChat = false,
            ),
        )
    }

    @Test
    fun messageNotification_shownWhenBackgrounded() {
        assertTrue(
            NotificationSettingsPolicy.shouldShowMessageNotification(
                messagesEnabled = true,
                appliedInbound = 1,
                appInForeground = false,
                hasOpenChat = true,
            ),
        )
    }

    @Test
    fun messageNotification_hiddenWhenReadingAChatInForeground() {
        assertFalse(
            NotificationSettingsPolicy.shouldShowMessageNotification(
                messagesEnabled = true,
                appliedInbound = 2,
                appInForeground = true,
                hasOpenChat = true,
            ),
        )
        assertTrue(
            NotificationSettingsPolicy.shouldShowMessageNotification(
                messagesEnabled = true,
                appliedInbound = 2,
                appInForeground = true,
                hasOpenChat = false,
            ),
        )
    }

    @Test
    fun callNotification_followsToggle() {
        assertTrue(NotificationSettingsPolicy.shouldShowCallNotification(true))
        assertFalse(NotificationSettingsPolicy.shouldShowCallNotification(false))
    }

    @Test
    fun messageCopy_defaultsAreGeneric() {
        val copy = NotificationSettingsPolicy.messageNotificationCopy(
            count = 1,
            showSenderName = false,
            showMessagePreview = false,
            senderName = "Аня",
            previewBody = "секрет",
        )
        assertEquals("Глаголица", copy.title)
        assertEquals("Новое сообщение", copy.body)
    }

    @Test
    fun messageCopy_optInNameAndPreview() {
        val named = NotificationSettingsPolicy.messageNotificationCopy(
            count = 1,
            showSenderName = true,
            showMessagePreview = false,
            senderName = "Аня",
            previewBody = "секрет",
        )
        assertEquals("Аня", named.title)
        assertEquals("Новое сообщение", named.body)

        val preview = NotificationSettingsPolicy.messageNotificationCopy(
            count = 4,
            showSenderName = true,
            showMessagePreview = true,
            senderName = "Аня",
            previewBody = "секрет",
        )
        assertEquals("Аня", preview.title)
        assertEquals("секрет", preview.body)
    }

    @Test
    fun systemPermissionBlocked_whenOsDenies() {
        assertTrue(NotificationSettingsPolicy.systemPermissionBlocked(false))
        assertFalse(NotificationSettingsPolicy.systemPermissionBlocked(true))
    }
}
