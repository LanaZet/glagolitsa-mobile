// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.jobs

/** Точка входа для фоновых воркеров платформы (WorkManager и т.п.). */
object BackgroundSyncBridge {
    var processPending: (suspend () -> Unit)? = null

    suspend fun runPendingSync() {
        processPending?.invoke()
    }
}