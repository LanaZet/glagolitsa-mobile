// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Account notification policy (Signal / Element X).
 *
 * Server already gates FCM wake. Client still must honor the same flags for
 * **local** banners after mailbox drain, and never put ciphertext in the OS shade
 * unless the user opted into preview.
 */
object NotificationSettingsPolicy {

    data class LocalCopy(
        val title: String,
        val body: String,
    )

    fun shouldShowMessageNotification(
        messagesEnabled: Boolean,
        appliedInbound: Int,
        appInForeground: Boolean,
        hasOpenChat: Boolean,
    ): Boolean {
        if (!messagesEnabled || appliedInbound <= 0) return false
        return !appInForeground || !hasOpenChat
    }

    fun shouldShowCallNotification(callsEnabled: Boolean): Boolean = callsEnabled

    /**
     * Local shade copy after decrypt. Never use FCM/APNs payload text.
     * Defaults match server [DefaultPreferences]: no name, no body.
     */
    fun messageNotificationCopy(
        count: Int,
        showSenderName: Boolean,
        showMessagePreview: Boolean,
        senderName: String?,
        previewBody: String?,
    ): LocalCopy {
        val name = senderName?.trim().orEmpty().takeIf { it.isNotEmpty() && showSenderName }
        val preview = previewBody?.trim().orEmpty().takeIf { it.isNotEmpty() && showMessagePreview }
        return LocalCopy(
            title = name ?: "Глаголица",
            body = when {
                preview != null -> preview
                count <= 1 -> "Новое сообщение"
                else -> "Новые сообщения: $count"
            },
        )
    }

    fun systemPermissionBlocked(systemNotificationsEnabled: Boolean): Boolean =
        !systemNotificationsEnabled
}
