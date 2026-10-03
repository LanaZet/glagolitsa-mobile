// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

data class LocalAttachmentRecord(
    val cacheId: String,
    val attachmentId: String? = null,
    val messageId: String? = null,
    val chatId: String,
    val ownerAccountId: String,
    val direction: String,
    val kind: String? = null,
    val mimeType: String? = null,
    val fileName: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val waveform: String? = null,
    val encryptedPath: String? = null,
    val thumbnailPath: String? = null,
    val thumbnailKey: String? = null,
    val thumbnailSize: Long = 0,
    val encryptedSize: Long = 0,
    val plaintextSize: Long = 0,
    val state: String,
    val lastAccessedAt: String? = null,
    val createdAt: String,
    val expiresAt: String? = null,
    val error: String? = null,
)

data class CachedEncryptedAttachment(
    val cacheId: String,
    val path: String,
    val encryptedSize: Long,
)

data class AttachmentCacheStats(
    val fileCount: Int,
    val encryptedBytes: Long,
    val thumbnailBytes: Long,
) {
    val totalBytes: Long get() = encryptedBytes + thumbnailBytes
}

object AttachmentDirection {
    const val OUTGOING = "outgoing"
    const val INCOMING = "incoming"
}

object AttachmentTransferState {
    const val PENDING = "pending"
    const val UPLOADING = "uploading"
    const val UPLOADED = "uploaded"
    const val DOWNLOADING = "downloading"
    const val DOWNLOADED = "downloaded"
    const val FAILED = "failed"
    const val PINNED = "pinned"
}

data class AttachmentRetentionPolicy(
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val immunityMs: Long = DEFAULT_IMMUNITY_MS,
    val maxDeletesPerRun: Int = 64,
    val maxFiles: Int = DEFAULT_MAX_FILES,
    val maxAgeMs: Long? = null,
    val mimeTypePrefixes: Set<String> = emptySet(),
    val excludedChatIds: Set<String> = emptySet(),
) {
    init {
        require(maxBytes >= 0) { "maxBytes must be >= 0" }
        require(immunityMs >= 0) { "immunityMs must be >= 0" }
        require(maxDeletesPerRun > 0) { "maxDeletesPerRun must be > 0" }
        require(maxFiles >= 0) { "maxFiles must be >= 0" }
        require(maxAgeMs == null || maxAgeMs >= 0) { "maxAgeMs must be >= 0" }
        require(mimeTypePrefixes.none { it.isBlank() }) { "mimeTypePrefixes must not contain blanks" }
        require(excludedChatIds.none { it.isBlank() }) { "excludedChatIds must not contain blanks" }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1024L * 1024L
        const val DEFAULT_IMMUNITY_MS = 60L * 60L * 1000L
        const val DEFAULT_MAX_FILES = 256
    }
}
