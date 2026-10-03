// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

/**
 * Крипто-идентичность устройства для аккаунта.
 * Приватный ключ не покидает устройство; на сервер уйдёт только public material (этап 2).
 */
data class DeviceIdentity(
    val accountId: String,
    val deviceId: String,
    val registrationId: Int,
    val identityPublicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as DeviceIdentity
        return accountId == other.accountId &&
            deviceId == other.deviceId &&
            registrationId == other.registrationId &&
            identityPublicKey.contentEquals(other.identityPublicKey)
    }

    override fun hashCode(): Int {
        var result = accountId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + registrationId
        result = 31 * result + identityPublicKey.contentHashCode()
        return result
    }
}

data class CryptoSelfTestResult(
    val identityGenerated: Boolean,
    val roundtripOk: Boolean,
) {
    val passed: Boolean get() = identityGenerated && roundtripOk
}