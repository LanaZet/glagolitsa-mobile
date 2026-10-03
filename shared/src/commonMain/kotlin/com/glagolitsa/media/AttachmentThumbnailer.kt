// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

expect object AttachmentThumbnailer {
    fun createThumbnail(bytes: ByteArray, mimeType: String?, maxEdgePx: Int = 1280): ByteArray?
}
