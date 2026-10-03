// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

enum class DisappearingMessageTtl(val seconds: Long?, val label: String) {
    OFF(null, "Без TTL"),
    HOUR(3_600L, "1 ч"),
    DAY(86_400L, "1 д"),
    WEEK(604_800L, "7 д"),
    ;
}