// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.util

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
fun ByteArray.encodeBase64(): String = Base64.encode(this)

@OptIn(ExperimentalEncodingApi::class)
fun String.decodeBase64(): ByteArray = Base64.decode(this)