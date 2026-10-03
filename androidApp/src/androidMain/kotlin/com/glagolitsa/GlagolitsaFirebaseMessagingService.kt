// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa

import com.glagolitsa.log.AppLog
import com.glagolitsa.platform.AppLifecycle
import com.glagolitsa.push.LocalMessageNotifier
import com.glagolitsa.push.PushWakeCoordinator
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Handles silent data pushes — no message body in notification payload. */
class GlagolitsaFirebaseMessagingService : FirebaseMessagingService() {
    override fun onCreate() {
        super.onCreate()
        // Process may start from FCM with no Activity — keep Application context for notifs.
        AppLifecycle.initApplication(application)
        LocalMessageNotifier.ensureChannels()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Privacy-safe: only opaque keys (type/call_id), never body/names.
        val type = message.data["type"].orEmpty()
        val callId = message.data["call_id"].orEmpty()
        AppLog.debug(
            "FCM onMessageReceived type=$type call_id=$callId " +
                "priority=${message.priority} keys=${message.data.keys.sorted().joinToString()}",
        )
        if (message.data.isNotEmpty()) {
            PushWakeCoordinator.onPushReceived(message.data)
        }
    }

    override fun onNewToken(token: String) {
        AppLog.debug("FCM onNewToken len=${token.length}")
        PushWakeCoordinator.onTokenRefreshed(token)
    }
}
