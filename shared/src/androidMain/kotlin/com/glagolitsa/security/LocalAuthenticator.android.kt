// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

actual class LocalAuthenticator(
    private val activity: FragmentActivity,
) {
    actual suspend fun availability(): LocalAuthAvailability = withContext(Dispatchers.Main.immediate) {
        when (BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> LocalAuthAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> LocalAuthAvailability.Unsupported
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> LocalAuthAvailability.HardwareUnavailable
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> LocalAuthAvailability.NoBiometricEnrolled
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> LocalAuthAvailability.SecurityUpdateRequired
            else -> LocalAuthAvailability.Unknown
        }
    }

    actual suspend fun authenticate(reason: String): LocalAuthResult = withContext(Dispatchers.Main.immediate) {
        if (availability() != LocalAuthAvailability.Available) {
            return@withContext LocalAuthResult.Failed(null)
        }
        suspendCancellableCoroutine<LocalAuthResult> { continuation ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) {
                            continuation.resume(LocalAuthResult.Success)
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!continuation.isActive) return
                        val result = when (errorCode) {
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_CANCELED -> LocalAuthResult.Cancelled
                            else -> LocalAuthResult.Failed(errString.toString())
                        }
                        continuation.resume(result)
                    }

                    override fun onAuthenticationFailed() {
                        // Keep the system prompt open; final success/error arrives later.
                    }
                },
            )
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Glagolitsa")
                .setSubtitle("Вход по отпечатку")
                .setDescription(reason)
                .setNegativeButtonText("Войти паролем")
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .build()

            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(promptInfo)
        }
    }
}
