// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.safety

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
expect fun SafetyNumberQrCode(
    payload: ByteArray,
    modifier: Modifier,
    foreground: Color,
    background: Color,
)