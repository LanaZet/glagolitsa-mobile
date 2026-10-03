// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.jobs

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.glagolitsa.sync.GlagolitsaSyncWorker
import java.util.concurrent.TimeUnit

private const val PERIODIC_WORK_NAME = "glagolitsa_sync_periodic"
private const val ONESHOT_WORK_NAME = "glagolitsa_sync_oneshot"

private var appContext: Context? = null

fun initBackgroundSyncScheduler(context: Context) {
    appContext = context.applicationContext
}

actual fun scheduleBackgroundSync() {
    val context = appContext ?: return
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val periodic = PeriodicWorkRequestBuilder<GlagolitsaSyncWorker>(15, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        PERIODIC_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        periodic,
    )

    val oneShot = OneTimeWorkRequestBuilder<GlagolitsaSyncWorker>()
        .setConstraints(constraints)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        ONESHOT_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        oneShot,
    )
}
