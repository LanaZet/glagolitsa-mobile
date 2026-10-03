// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Where the soft update card may appear. The hard gate is always full-screen.
 *
 * Soft prompt is a home-shell card (chat list / tabs). It must never overlay
 * an open conversation: back, call and chat menu have to stay fully tappable.
 */
object ClientUpdatePromptPolicy {

    /**
     * @param onMainShell true on the tab shell (чаты / звонки / настройки).
     *        False on ChatDetail, thread, chat info, login, call, etc.
     */
    fun shouldShowSoftBanner(
        kind: UpdateDecisionKind?,
        dismissed: Boolean,
        onMainShell: Boolean,
    ): Boolean = kind == UpdateDecisionKind.SOFT && !dismissed && onMainShell

    fun shouldShowHardGate(kind: UpdateDecisionKind?): Boolean =
        kind == UpdateDecisionKind.HARD
}
