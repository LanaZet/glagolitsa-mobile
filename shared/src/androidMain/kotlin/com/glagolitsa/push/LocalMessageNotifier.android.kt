// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.glagolitsa.platform.AppLifecycle

private const val CHANNEL_MESSAGES = "glag_messages"
private const val CHANNEL_CALLS = "glag_calls"
private const val NOTIF_ID_MESSAGES = 41001
private const val NOTIF_ID_CALL_BASE = 42000

actual object LocalMessageNotifier {
    actual fun ensureChannels() {
        val context = appContext() ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "Сообщения",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Новые сообщения (текст строится локально после расшифровки)"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALLS,
                "Звонки",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Входящие и пропущенные звонки"
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
    }

    actual fun showNewMessages(
        count: Int,
        chatId: String?,
        previewTitle: String?,
        previewBody: String?,
    ) {
        if (count < 1) return
        val context = appContext() ?: return
        ensureChannels()
        val title = previewTitle?.takeIf { it.isNotBlank() } ?: "Глаголица"
        val body = when {
            !previewBody.isNullOrBlank() -> previewBody
            count == 1 -> "Новое сообщение"
            else -> "Новые сообщения: $count"
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, CHANNEL_MESSAGES)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchAppPendingIntent(context, chatId, null))
            .setNumber(count)
            .build()
        nm.notify(NOTIF_ID_MESSAGES, notification)
    }

    actual fun showIncomingCall(callId: String, genericBody: String) {
        val context = appContext() ?: return
        ensureChannels()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val fullScreen = launchAppPendingIntent(context, null, callId)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, CHANNEL_CALLS)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
                .setPriority(android.app.Notification.PRIORITY_MAX)
                .setDefaults(android.app.Notification.DEFAULT_ALL)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Входящий звонок")
            .setContentText(genericBody.ifBlank { "Глаголица" })
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setCategory(android.app.Notification.CATEGORY_CALL)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            .setTimeoutAfter(45_000L)
            .build()
        nm.notify(callNotificationId(callId), notification)
    }

    actual fun showMissedCall(callId: String) {
        val context = appContext() ?: return
        ensureChannels()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Cancel ongoing ring first.
        nm.cancel(callNotificationId(callId))
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, CHANNEL_CALLS)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
                .setPriority(android.app.Notification.PRIORITY_HIGH)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Глаголица")
            .setContentText("Пропущенный звонок")
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(launchAppPendingIntent(context, null, callId))
            .setCategory(android.app.Notification.CATEGORY_MISSED_CALL)
            .build()
        nm.notify(callNotificationId(callId), notification)
    }

    actual fun cancelIncomingCall(callId: String) {
        val context = appContext() ?: return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(callNotificationId(callId))
    }

    actual fun cancelMessages() {
        val context = appContext() ?: return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID_MESSAGES)
    }

    private fun appContext(): Context? =
        AppLifecycle.applicationContextOrNull()
            ?: AppLifecycle.activityOrNull()?.applicationContext

    private fun launchAppPendingIntent(
        context: Context,
        chatId: String?,
        callId: String?,
    ): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setPackage(context.packageName)
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        if (!chatId.isNullOrBlank()) {
            launch.putExtra("chat_id", chatId)
        }
        if (!callId.isNullOrBlank()) {
            launch.putExtra("call_id", callId)
        }
        val requestCode = (callId ?: chatId)?.hashCode() ?: 0
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, requestCode, launch, flags)
    }

    private fun callNotificationId(callId: String): Int =
        NOTIF_ID_CALL_BASE + (callId.hashCode() and 0xffff)
}
