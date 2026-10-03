// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.security

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.getSystemService

actual object SecureClipboard {
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingClear: Runnable? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    actual fun copyWithAutoClear(label: String, text: String, clearAfterMs: Long) {
        val context = appContext ?: return
        val clipboard = context.getSystemService<ClipboardManager>() ?: return
        pendingClear?.let(handler::removeCallbacks)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        val clearTask = Runnable {
            if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == text) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, ""))
                }
            }
        }
        pendingClear = clearTask
        handler.postDelayed(clearTask, clearAfterMs)
    }
}
