// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.security

import androidx.compose.runtime.Composable

/** Блокирует скриншоты/запись экрана (FLAG_SECURE) пока composable на экране. */
@Composable
expect fun SecureWindowEffect(enabled: Boolean = true)