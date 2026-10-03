// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Радиусы скругления Design System 1.0. */
object GlagolitsaShapes {
    val xs = RoundedCornerShape(8.dp)
    /** Радиус полей ввода — 10dp по референсу INPUTS. */
    val input = RoundedCornerShape(10.dp)
    val sm = RoundedCornerShape(12.dp)
    val md = RoundedCornerShape(16.dp)
    val lg = RoundedCornerShape(24.dp)
    val xl = RoundedCornerShape(28.dp)
    val pill = RoundedCornerShape(percent = 50)
}