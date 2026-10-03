// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.push

import com.glagolitsa.log.AppLog
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

actual class PushTokenProvider actual constructor() {
    actual val platform: String = "android"

    actual suspend fun currentToken(): String? = withContext(Dispatchers.IO) {
        runCatching {
            Tasks.await(
                FirebaseMessaging.getInstance().token,
                15.seconds.inWholeMilliseconds,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }.onFailure { err ->
            // Stub google-services.json / missing Firebase project → no token, no server register.
            AppLog.warning(
                "push FCM token failed: ${err::class.simpleName}:${err.message.orEmpty()}",
            )
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
