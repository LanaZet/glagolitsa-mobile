// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.call

/** Phase 4 will wire LiveKit + CallKit; until then in-app call UI degrades safely. */
actual fun createCallMediaEngine(): CallMediaEngine = NoopCallMediaEngine()
