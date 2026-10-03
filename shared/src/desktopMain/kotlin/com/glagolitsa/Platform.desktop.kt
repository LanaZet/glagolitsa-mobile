// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa

class DesktopPlatform : Platform {
    override val name: String = "Desktop JVM"
}

actual fun getPlatform(): Platform = DesktopPlatform()