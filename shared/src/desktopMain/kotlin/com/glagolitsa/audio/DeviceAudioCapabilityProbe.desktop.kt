// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.audio

actual fun probeDeviceAudioCapability(): DeviceAudioCapability = DeviceAudioCapability(
    cpuCores = Runtime.getRuntime().availableProcessors(),
    ramMb = 8192,
    batteryPercent = 100,
    batteryCharging = true,
    lowRamDevice = false,
)
