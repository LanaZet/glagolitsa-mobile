// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.push

import com.glagolitsa.platform.AppLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bridges platform push delivery (FCM/APNs/UnifiedPush) into shared message queue sync.
 * Privacy-safe: only opaque data keys, never message plaintext from the push payload.
 *
 * Flow (Signal push-to-sync + Firebase 2025 visible UI):
 * 1) wake with opaque type
 * 2) fetch/decrypt mailbox
 * 3) show **local** system notification (not FCM notification block)
 */
object PushWakeCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var onSyncQueue: (suspend () -> PushWakeResult)? = null
    private var onPrekeysLow: (suspend () -> Unit)? = null
    private var onTokenRefresh: (suspend (String) -> Unit)? = null
    private var onIncomingCall: (suspend (Map<String, String>) -> Unit)? = null
    private val pendingIncomingCallMutex = Mutex()
    private val pendingIncomingCalls = mutableListOf<Map<String, String>>()

    fun bind(
        onSyncQueue: suspend () -> PushWakeResult,
        onPrekeysLow: (suspend () -> Unit)? = null,
        onTokenRefresh: (suspend (String) -> Unit)? = null,
        onIncomingCall: (suspend (Map<String, String>) -> Unit)? = null,
    ) {
        this.onSyncQueue = onSyncQueue
        this.onPrekeysLow = onPrekeysLow
        this.onTokenRefresh = onTokenRefresh
        this.onIncomingCall = onIncomingCall
        LocalMessageNotifier.ensureChannels()
        if (onIncomingCall != null) {
            scope.launch { drainPendingIncomingCalls() }
        }
    }

    fun clear() {
        onSyncQueue = null
        onPrekeysLow = null
        onTokenRefresh = null
        onIncomingCall = null
    }

    fun onPushReceived(data: Map<String, String>) {
        when (data["type"]) {
            "new_message",
            "message_sync",
            -> scope.launch {
                val sync = onSyncQueue
                if (sync == null) {
                    // Cold start from FCM: repository not bound yet — still show generic wake
                    // so the user is not left without a notification until they open the app.
                    LocalMessageNotifier.showNewMessages(count = 1)
                    return@launch
                }
                val result = runCatching { sync() }.getOrDefault(PushWakeResult.Empty)
                presentAfterMailboxDrain(
                    result = result,
                    // If drain applied nothing (or only the open chat), still poke when backgrounded.
                    fallbackWhenBackgrounded = true,
                )
            }
            "prekeys.low" -> scope.launch { onPrekeysLow?.invoke() }
            "incoming_call" -> scope.launch {
                // Local UI first (Signal: show ring before mailbox/enroll finishes).
                val callId = data["call_id"].orEmpty().ifBlank { "call" }
                LocalMessageNotifier.showIncomingCall(callId)
                val payload = data.toMap()
                val handler = onIncomingCall
                if (handler != null) {
                    runCatching { handler(payload) }
                } else {
                    pendingIncomingCallMutex.withLock {
                        pendingIncomingCalls += payload
                    }
                }
            }
            "missed_call" -> scope.launch {
                LocalMessageNotifier.showMissedCall(data["call_id"].orEmpty().ifBlank { "call" })
            }
        }
    }

    fun onTokenRefreshed(token: String) {
        scope.launch { onTokenRefresh?.invoke(token) }
    }

    /**
     * Single place for local system UI after mailbox drain.
     * Used by FCM/APNs wake **and** background queue poll (WorkManager / bootstrap),
     * so OEM builds that drop data-only FCM still surface inbound mail.
     *
     * Never takes title/body from the push payload — only [PushWakeResult] (local decrypt/prefs).
     */
    fun presentAfterMailboxDrain(
        result: PushWakeResult,
        fallbackWhenBackgrounded: Boolean = false,
    ) {
        if (result.shouldShowLocalNotification) {
            LocalMessageNotifier.showNewMessages(
                count = maxOf(1, result.appliedInbound),
                chatId = result.lastChatId,
                previewTitle = result.previewTitle,
                previewBody = result.previewBody,
            )
            return
        }
        if (fallbackWhenBackgrounded && !AppLifecycle.isInForeground()) {
            LocalMessageNotifier.showNewMessages(count = 1)
        }
    }

    private suspend fun drainPendingIncomingCalls() {
        val pending = pendingIncomingCallMutex.withLock {
            if (pendingIncomingCalls.isEmpty()) {
                emptyList()
            } else {
                pendingIncomingCalls.toList().also { pendingIncomingCalls.clear() }
            }
        }
        if (pending.isEmpty()) return
        pending.forEach { payload ->
            onIncomingCall?.invoke(payload)
        }
    }
}

/** Result of wake → mailbox drain. */
data class PushWakeResult(
    val appliedInbound: Int = 0,
    val lastChatId: String? = null,
    /** True when we applied messages the user is not currently viewing. */
    val shouldShowLocalNotification: Boolean = false,
    /** Optional local-only enrich (after decrypt + prefs); never from FCM. */
    val previewTitle: String? = null,
    val previewBody: String? = null,
) {
    companion object {
        val Empty = PushWakeResult()
    }
}
