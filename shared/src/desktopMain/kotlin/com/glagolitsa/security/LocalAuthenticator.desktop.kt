// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.security

actual class LocalAuthenticator {
    actual suspend fun availability(): LocalAuthAvailability = LocalAuthAvailability.Unsupported

    actual suspend fun authenticate(reason: String): LocalAuthResult =
        LocalAuthResult.Failed("Local authentication is not supported on desktop")
}
