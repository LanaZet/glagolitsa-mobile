// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.writeToURL

@OptIn(ExperimentalForeignApi::class)
internal fun iosTemporaryFileUrl(prefix: String, extension: String): NSURL {
    val safePrefix = prefix.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        .ifBlank { "glagolitsa-media" }
    val safeExtension = extension.lowercase().filter(Char::isLetterOrDigit).ifBlank { "bin" }
    return NSURL.fileURLWithPath("${NSTemporaryDirectory()}$safePrefix-${NSUUID().UUIDString}.$safeExtension")
}

@OptIn(ExperimentalForeignApi::class)
internal fun iosWriteTemporaryFile(bytes: ByteArray, prefix: String, extension: String): NSURL {
    val url = iosTemporaryFileUrl(prefix, extension)
    check(bytes.toNSData().writeToURL(url, atomically = true)) { "failed to write temporary media" }
    iosProtectTemporaryFile(url)
    return url
}

@OptIn(ExperimentalForeignApi::class)
internal fun iosProtectTemporaryFile(url: NSURL) {
    val path = url.path ?: error("temporary file path is unavailable")
    check(
        NSFileManager.defaultManager.setAttributes(
            mapOf(NSFileProtectionKey to NSFileProtectionComplete),
            ofItemAtPath = path,
            error = null,
        ),
    ) { "failed to protect temporary media" }
}

@OptIn(ExperimentalForeignApi::class)
internal fun iosRemoveFile(url: NSURL?) {
    val path = url?.path ?: return
    if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
}
