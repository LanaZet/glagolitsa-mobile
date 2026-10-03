// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

import com.glagolitsa.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionStore {
    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    private val _refreshToken = MutableStateFlow<String?>(null)
    val refreshToken: StateFlow<String?> = _refreshToken.asStateFlow()

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    /** Нужен повторный вход паролем (refresh мёртв), локальные чаты сохраняются. */
    private val _reauthRequired = MutableStateFlow(false)
    val reauthRequired: StateFlow<Boolean> = _reauthRequired.asStateFlow()

    fun setSession(token: String, user: User, refreshToken: String? = null) {
        _token.value = token
        _user.value = user
        _refreshToken.value = refreshToken
        _reauthRequired.value = false
    }

    fun updateUser(user: User) {
        _user.value = user
    }

    fun setUserOnly(user: User) {
        _user.value = user
    }

    fun clearAccessToken() {
        _token.value = null
    }

    fun clearRefreshToken() {
        _refreshToken.value = null
    }

    fun markReauthRequired() {
        _reauthRequired.value = true
        _token.value = null
    }

    fun clearReauthRequired() {
        _reauthRequired.value = false
    }

    fun clear() {
        _token.value = null
        _refreshToken.value = null
        _user.value = null
        _reauthRequired.value = false
    }
}
