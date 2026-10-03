// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.push

actual object LocalMessageNotifier {
    actual fun ensureChannels() {}

    actual fun showNewMessages(
        count: Int,
        chatId: String?,
        previewTitle: String?,
        previewBody: String?,
    ) {
        // Desktop: no OSPNS local tray yet.
    }

    actual fun showIncomingCall(callId: String, genericBody: String) {}

    actual fun showMissedCall(callId: String) {}

    actual fun cancelIncomingCall(callId: String) {}

    actual fun cancelMessages() {}
}
