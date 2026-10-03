// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.audio

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.glagolitsa.platform.AppLifecycle

actual fun probeDeviceAudioCapability(): DeviceAudioCapability {
    val context = AppLifecycle.applicationContextOrNull() ?: return DeviceAudioCapability.Unknown
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    activityManager?.getMemoryInfo(memInfo)
    val ramMb = if (memInfo.totalMem > 0L) {
        (memInfo.totalMem / (1024L * 1024L)).toInt()
    } else {
        0
    }
    val lowRam = activityManager?.isLowRamDevice == true
    val battery = readBattery(context)
    return DeviceAudioCapability(
        cpuCores = Runtime.getRuntime().availableProcessors(),
        ramMb = ramMb,
        batteryPercent = battery.first,
        batteryCharging = battery.second,
        lowRamDevice = lowRam,
    )
}

private fun readBattery(context: Context): Pair<Int?, Boolean> {
    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        context.registerReceiver(null, filter)
    } ?: return null to false
    val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
    val percent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else null
    val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    return percent to (plugged != 0)
}
