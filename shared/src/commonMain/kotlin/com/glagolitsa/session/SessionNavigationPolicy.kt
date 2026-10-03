// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.session

/**
 * Pure session → navigation decisions (no Compose / no IO).
 *
 * Pattern used by Element X (session store + route mapping), Mattermost (session actions),
 * and Telegram-style soft recovery: temporary access-token loss is **not** a logout.
 * Only hard reauth or full clear of user identity may leave the chat shell.
 */
object SessionNavigationPolicy {

    /** After [MessengerRepository.restoreSession], pick first screen. */
    fun initialRouteIsMain(sessionRestored: Boolean): Boolean = sessionRestored

    /**
     * Soft reauth: refresh is dead, user identity still known.
     * UI shows login with prefilled username; local chats stay on disk.
     */
    fun shouldRouteToReauthLogin(reauthRequired: Boolean, hasUser: Boolean): Boolean =
        reauthRequired && hasUser

    /**
     * Full logout / never logged in: no user and no access token.
     * Must not fire while access token is null but user is still bound
     * (mid-refresh / offline recovery).
     */
    fun shouldRouteToLoggedOutLogin(
        hasAccessToken: Boolean,
        hasUser: Boolean,
        alreadyOnAuthScreen: Boolean,
    ): Boolean = !hasAccessToken && !hasUser && !alreadyOnAuthScreen

    /**
     * User may stay on ChatDetail / Main while access token is temporarily null
     * and we are still recovering (Telegram-style maintainSession loop).
     */
    fun shouldKeepAuthenticatedShell(
        hasUser: Boolean,
        reauthRequired: Boolean,
    ): Boolean = hasUser && !reauthRequired

    /**
     * Background recovery loop condition from MessengerApp:
     * retry refresh while user is present, token missing, reauth not forced.
     */
    fun shouldAttemptSessionRecovery(
        hasUser: Boolean,
        hasAccessToken: Boolean,
        reauthRequired: Boolean,
    ): Boolean = hasUser && !hasAccessToken && !reauthRequired

    /**
     * Whether the open chat screen must be abandoned for login.
     * True only for soft reauth or full identity wipe — not for transient 401 recovery.
     */
    fun shouldLeaveChatForAuth(
        hasUser: Boolean,
        hasAccessToken: Boolean,
        reauthRequired: Boolean,
    ): Boolean {
        if (shouldRouteToReauthLogin(reauthRequired, hasUser)) return true
        if (!hasUser && !hasAccessToken) return true
        return false
    }
}
