// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.push

/**
 * Local system notification after push-to-sync (Signal model).
 * Never uses FCM payload text — copy is generic or filled from local decrypt only.
 */
expect object LocalMessageNotifier {
    fun ensureChannels()

    /**
     * Show a user-visible notification for newly applied inbound messages.
     * [count] >= 1; body stays generic unless [preview] is provided from local plaintext.
     */
    fun showNewMessages(
        count: Int,
        chatId: String? = null,
        previewTitle: String? = null,
        previewBody: String? = null,
    )

    fun showIncomingCall(callId: String, genericBody: String = "Входящий звонок")

    fun showMissedCall(callId: String)

    fun cancelIncomingCall(callId: String)

    fun cancelMessages()
}
