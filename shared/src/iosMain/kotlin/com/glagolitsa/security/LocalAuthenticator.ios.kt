// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.security

import kotlinx.cinterop.ExperimentalForeignApi

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorAppCancel
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
actual class LocalAuthenticator {
    actual suspend fun availability(): LocalAuthAvailability {
        val context = LAContext()
        val biometrics = context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error = null)
        if (biometrics) return LocalAuthAvailability.Available
        val passcode = context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)
        return if (passcode) LocalAuthAvailability.NoBiometricEnrolled else LocalAuthAvailability.Unsupported
    }

    actual suspend fun authenticate(reason: String): LocalAuthResult =
        suspendCancellableCoroutine { cont ->
            val context = LAContext()
            context.evaluatePolicy(
                policy = LAPolicyDeviceOwnerAuthentication,
                localizedReason = reason,
            ) { success, error ->
                if (!cont.isActive) return@evaluatePolicy
                when {
                    success -> cont.resume(LocalAuthResult.Success)
                    else -> {
                        val code = error?.code
                        val cancelled = code == LAErrorUserCancel ||
                            code == LAErrorSystemCancel ||
                            code == LAErrorAppCancel
                        cont.resume(
                            if (cancelled) LocalAuthResult.Cancelled
                            else LocalAuthResult.Failed(error?.localizedDescription),
                        )
                    }
                }
            }
        }
}
