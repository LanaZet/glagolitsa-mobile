// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.push

import platform.Foundation.NSLog
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Local notifications after mailbox drain (Signal model).
 * Generic copy by default — never trusts APNs body for plaintext.
 */
actual object LocalMessageNotifier {
    private const val MESSAGE_CATEGORY = "glagolitsa.messages"
    private const val CALL_CATEGORY = "glagolitsa.calls"

    actual fun ensureChannels() {
        // iOS has no Android-style channels; categories reserved for later.
    }

    actual fun showNewMessages(
        count: Int,
        chatId: String?,
        previewTitle: String?,
        previewBody: String?,
    ) {
        val title = previewTitle?.takeIf { it.isNotBlank() } ?: "Глаголица"
        val body = when {
            !previewBody.isNullOrBlank() -> previewBody
            count <= 1 -> "Новое сообщение"
            else -> "Новые сообщения: $count"
        }
        present(
            identifier = "msg-${chatId ?: "inbox"}-${currentTime()}",
            title = title,
            body = body,
            category = MESSAGE_CATEGORY,
        )
    }

    actual fun showIncomingCall(callId: String, genericBody: String) {
        present(
            identifier = "call-in-$callId",
            title = "Глаголица",
            body = genericBody,
            category = CALL_CATEGORY,
        )
    }

    actual fun showMissedCall(callId: String) {
        cancelIncomingCall(callId)
        present(
            identifier = "call-missed-$callId",
            title = "Глаголица",
            body = "Пропущенный звонок",
            category = CALL_CATEGORY,
        )
    }

    actual fun cancelIncomingCall(callId: String) {
        val identifiers = listOf("call-in-$callId")
        UNUserNotificationCenter.currentNotificationCenter()
            .removePendingNotificationRequestsWithIdentifiers(identifiers)
        UNUserNotificationCenter.currentNotificationCenter()
            .removeDeliveredNotificationsWithIdentifiers(identifiers)
    }

    actual fun cancelMessages() {
        UNUserNotificationCenter.currentNotificationCenter()
            .removeAllDeliveredNotifications()
    }

    private fun present(identifier: String, title: String, body: String, category: String) {
        dispatch_async(dispatch_get_main_queue()) {
            val content = UNMutableNotificationContent().apply {
                setTitle(title)
                setBody(body)
                setSound(UNNotificationSound.defaultSound)
                setCategoryIdentifier(category)
            }
            val request = UNNotificationRequest.requestWithIdentifier(
                identifier = identifier,
                content = content,
                trigger = null,
            )
            UNUserNotificationCenter.currentNotificationCenter()
                .addNotificationRequest(request) { error ->
                    if (error != null) {
                        NSLog("LocalMessageNotifier error: %@", error.localizedDescription)
                    }
                }
        }
    }

    private fun currentTime(): Long = com.glagolitsa.currentTimeMillis()
}
