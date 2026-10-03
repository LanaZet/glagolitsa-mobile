// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

/**
 * Stable per-install identifier that survives app process restarts.
 * Used to derive deterministic device_ids so reinstall does not create zombie devices.
 */
expect fun installationSeed(): String
