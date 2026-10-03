// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pending text/URL shared into the app from an external source
 * (Telegram, YouTube, browser share sheet, etc.).
 */
data class IncomingShare(
    val text: String,
    /** Optional EXTRA_SUBJECT / source hint for the picker header. */
    val subject: String? = null,
)

/**
 * Process-wide inbox for ACTION_SEND (and future desktop paste-share).
 * UI observes [pending]; after send or dismiss it calls [clear].
 */
object IncomingShareBus {
    private val _pending = MutableStateFlow<IncomingShare?>(null)
    val pending: StateFlow<IncomingShare?> = _pending.asStateFlow()

    fun offer(share: IncomingShare) {
        if (share.text.isBlank()) return
        _pending.value = share
    }

    fun clear() {
        _pending.value = null
    }
}
