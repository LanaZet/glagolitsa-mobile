// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

/**
 * Swift Keychain host, installed from [iosApp] before [MainViewController] starts.
 * Kotlin/Native cannot pass HashMap/NSDictionary into SecItem*.
 */
object IosSessionKeychainHost {
    var backend: IosSessionKeychainBackend? = null
}

interface IosSessionKeychainBackend {
    fun write(account: String, value: String): Int
    fun read(account: String): String?
    fun remove(account: String)
}
