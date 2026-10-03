// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.glagolitsa.jobs.BackgroundSyncBridge
import com.glagolitsa.log.AppLog

class GlagolitsaSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            BackgroundSyncBridge.runPendingSync()
            Result.success()
        }.getOrElse {
            AppLog.warning("background sync failed: ${it.message}")
            Result.retry()
        }
    }
}
