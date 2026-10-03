// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.passkey

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
internal object Base64Url {
    private val codec = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    fun encode(data: ByteArray): String = codec.encode(data)

    fun decode(raw: String): ByteArray = codec.decode(raw.trim())
}
