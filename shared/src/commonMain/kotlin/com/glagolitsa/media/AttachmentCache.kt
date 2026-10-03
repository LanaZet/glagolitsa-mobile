// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import com.glagolitsa.currentIsoTimestamp
import com.glagolitsa.currentTimeMillis
import com.glagolitsa.crypto.FileAttachmentCrypto
import com.glagolitsa.db.LocalDataStore
import com.glagolitsa.parseIsoTimestampMillis
import com.glagolitsa.util.decodeBase64
import com.glagolitsa.util.encodeBase64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.glagolitsa.util.sha256Hex

class AttachmentCache(
    local: LocalDataStore,
    private val files: MediaFileStore = PlatformMediaFileStore(),
) {
    val metadata = AttachmentMetadataStore(local)

    suspend fun putOutgoingEncrypted(
        cacheId: String,
        encryptedBytes: ByteArray,
        chatId: String,
        ownerAccountId: String,
        messageId: String?,
        fileName: String?,
        mimeType: String?,
        plaintextSize: Long,
        kind: AttachmentKind = resolveAttachmentKind(mimeType, fileName),
        width: Int? = null,
        height: Int? = null,
        durationMs: Long? = null,
        waveform: String? = null,
        expiresAt: String? = null,
        createdAt: String = currentIsoTimestamp(),
    ): LocalAttachmentRecord {
        require(cacheId.isNotBlank()) { "cacheId is required" }
        require(encryptedBytes.isNotEmpty()) { "encryptedBytes is required" }
        val path = files.writeEncrypted(cacheId, encryptedBytes)
        val record = LocalAttachmentRecord(
            cacheId = cacheId,
            messageId = messageId,
            chatId = chatId,
            ownerAccountId = ownerAccountId,
            direction = AttachmentDirection.OUTGOING,
            kind = kind.wireName,
            mimeType = mimeType,
            fileName = fileName,
            width = width,
            height = height,
            durationMs = durationMs,
            waveform = waveform,
            encryptedPath = path,
            encryptedSize = encryptedBytes.size.toLong(),
            plaintextSize = plaintextSize,
            state = AttachmentTransferState.PENDING,
            lastAccessedAt = createdAt,
            createdAt = createdAt,
            expiresAt = expiresAt,
        )
        metadata.upsert(record)
        return record
    }

    suspend fun putOutgoingEncryptedFile(
        cacheId: String,
        encryptedPath: String,
        encryptedSize: Long,
        chatId: String,
        ownerAccountId: String,
        messageId: String?,
        fileName: String?,
        mimeType: String?,
        plaintextSize: Long,
        kind: AttachmentKind = resolveAttachmentKind(mimeType, fileName),
        width: Int? = null,
        height: Int? = null,
        durationMs: Long? = null,
        waveform: String? = null,
        expiresAt: String? = null,
        createdAt: String = currentIsoTimestamp(),
    ): LocalAttachmentRecord {
        require(cacheId.isNotBlank()) { "cacheId is required" }
        require(encryptedSize > 0) { "encryptedSize is required" }
        val path = files.copyEncrypted(encryptedPath, cacheId)
        if (path != encryptedPath) {
            runCatching { files.delete(encryptedPath) }
        }
        val record = LocalAttachmentRecord(
            cacheId = cacheId,
            messageId = messageId,
            chatId = chatId,
            ownerAccountId = ownerAccountId,
            direction = AttachmentDirection.OUTGOING,
            kind = kind.wireName,
            mimeType = mimeType,
            fileName = fileName,
            width = width,
            height = height,
            durationMs = durationMs,
            waveform = waveform,
            encryptedPath = path,
            encryptedSize = encryptedSize,
            plaintextSize = plaintextSize,
            state = AttachmentTransferState.PENDING,
            lastAccessedAt = createdAt,
            createdAt = createdAt,
            expiresAt = expiresAt,
        )
        metadata.upsert(record)
        return record
    }

    suspend fun putDownloadedEncrypted(
        cacheId: String,
        attachmentId: String,
        encryptedBytes: ByteArray,
        chatId: String,
        ownerAccountId: String,
        messageId: String?,
        fileName: String?,
        mimeType: String?,
        plaintextSize: Long,
        kind: AttachmentKind = resolveAttachmentKind(mimeType, fileName),
        width: Int? = null,
        height: Int? = null,
        durationMs: Long? = null,
        waveform: String? = null,
        expiresAt: String? = null,
        createdAt: String = currentIsoTimestamp(),
    ): LocalAttachmentRecord {
        require(attachmentId.isNotBlank()) { "attachmentId is required" }
        val path = files.writeEncrypted(cacheId, encryptedBytes)
        val record = LocalAttachmentRecord(
            cacheId = cacheId,
            attachmentId = attachmentId,
            messageId = messageId,
            chatId = chatId,
            ownerAccountId = ownerAccountId,
            direction = AttachmentDirection.INCOMING,
            kind = kind.wireName,
            mimeType = mimeType,
            fileName = fileName,
            width = width,
            height = height,
            durationMs = durationMs,
            waveform = waveform,
            encryptedPath = path,
            encryptedSize = encryptedBytes.size.toLong(),
            plaintextSize = plaintextSize,
            state = AttachmentTransferState.DOWNLOADED,
            lastAccessedAt = createdAt,
            createdAt = createdAt,
            expiresAt = expiresAt,
        )
        metadata.upsert(record)
        return record
    }

    suspend fun readEncrypted(cacheId: String, accessedAt: String = currentIsoTimestamp()): ByteArray? {
        val record = metadata.find(cacheId) ?: return null
        val path = record.encryptedPath ?: return null
        val bytes = files.readEncrypted(path) ?: return null
        metadata.touch(cacheId, accessedAt)
        return bytes
    }

    suspend fun openEncrypted(cacheId: String, accessedAt: String = currentIsoTimestamp()): CachedEncryptedAttachment? {
        val record = metadata.find(cacheId) ?: return null
        val path = record.encryptedPath ?: return null
        if (!files.exists(path)) return null
        metadata.touch(cacheId, accessedAt)
        return CachedEncryptedAttachment(
            cacheId = cacheId,
            path = path,
            encryptedSize = record.encryptedSize,
        )
    }

    suspend fun hasEncrypted(cacheId: String): Boolean {
        val record = metadata.find(cacheId) ?: return false
        val path = record.encryptedPath ?: return false
        return files.exists(path)
    }

    suspend fun readEncryptedChunk(
        handle: CachedEncryptedAttachment,
        offset: Long,
        maxBytes: Int,
        accessedAt: String = currentIsoTimestamp(),
    ): ByteArray? {
        val bytes = files.readEncryptedChunk(handle.path, offset, maxBytes) ?: return null
        metadata.touch(handle.cacheId, accessedAt)
        return bytes
    }

    suspend fun markUploading(cacheId: String, accessedAt: String = currentIsoTimestamp()) {
        metadata.markState(cacheId, AttachmentTransferState.UPLOADING, accessedAt = accessedAt)
    }

    suspend fun markUploaded(cacheId: String, attachmentId: String, accessedAt: String = currentIsoTimestamp()) {
        metadata.markUploaded(cacheId, attachmentId, accessedAt)
    }

    suspend fun markFailed(cacheId: String, error: String?, accessedAt: String = currentIsoTimestamp()) {
        metadata.markState(cacheId, AttachmentTransferState.FAILED, error = error, accessedAt = accessedAt)
    }

    suspend fun storeThumbnailPlaintext(
        cacheId: String,
        thumbnailPlaintext: ByteArray?,
        accessedAt: String = currentIsoTimestamp(),
    ): Boolean {
        if (thumbnailPlaintext == null || thumbnailPlaintext.isEmpty()) return false
        val existing = metadata.find(cacheId) ?: return false
        val encrypted = withContext(Dispatchers.Default) {
            FileAttachmentCrypto.encrypt(thumbnailPlaintext)
        }
        val path = files.writeEncrypted("$cacheId-thumbnail", encrypted.ciphertext)
        if (existing.thumbnailPath != null && existing.thumbnailPath != path) {
            runCatching { files.delete(existing.thumbnailPath) }
        }
        metadata.setThumbnail(
            cacheId = cacheId,
            thumbnailPath = path,
            thumbnailKey = encrypted.fileKey.encodeBase64(),
            thumbnailSize = encrypted.ciphertext.size.toLong(),
            accessedAt = accessedAt,
        )
        return true
    }

    suspend fun readThumbnail(cacheId: String, accessedAt: String = currentIsoTimestamp()): ByteArray? {
        val record = metadata.find(cacheId) ?: return null
        val path = record.thumbnailPath ?: return null
        val key = record.thumbnailKey?.let { runCatching { it.decodeBase64() }.getOrNull() } ?: return null
        val encrypted = files.readEncrypted(path) ?: return null
        val plaintext = withContext(Dispatchers.Default) {
            FileAttachmentCrypto.decrypt(key, encrypted)
        } ?: return null
        metadata.touch(cacheId, accessedAt)
        return plaintext
    }

    suspend fun delete(cacheId: String) {
        metadata.find(cacheId)?.let { record ->
            record.encryptedPath?.let { files.delete(it) }
            record.thumbnailPath?.let { files.delete(it) }
        }
        metadata.delete(cacheId)
    }

    suspend fun clearChat(chatId: String) {
        metadata.listForChat(chatId).forEach { delete(it.cacheId) }
    }

    suspend fun clearAll() {
        metadata.listAll().forEach { delete(it.cacheId) }
    }

    suspend fun stats(chatId: String? = null): AttachmentCacheStats {
        val records = if (chatId == null) metadata.listAll() else metadata.listForChat(chatId)
        return AttachmentCacheStats(
            fileCount = records.size,
            encryptedBytes = records.sumOf { it.encryptedSize },
            thumbnailBytes = records.sumOf { it.thumbnailSize },
        )
    }

    suspend fun trim(
        policy: AttachmentRetentionPolicy = AttachmentRetentionPolicy(),
        nowMs: Long = currentTimeMillis(),
        nowIso: String = currentIsoTimestamp(),
    ): Int {
        var deleted = 0
        metadata.expired(nowIso).forEach { record ->
            if (isTransferProtected(record)) return@forEach
            if (deleted >= policy.maxDeletesPerRun) return deleted
            delete(record.cacheId)
            deleted++
        }
        val candidates = metadata.trimCandidates(policy.maxDeletesPerRun)
            .filter { it.isEligibleForPolicy(policy) }
            .filterNot { isImmune(it, nowMs, policy.immunityMs) }
            .toMutableList()
        val deletedIds = mutableSetOf<String>()

        policy.maxAgeMs?.let { maxAgeMs ->
            val iterator = candidates.iterator()
            while (iterator.hasNext() && deleted < policy.maxDeletesPerRun) {
                val candidate = iterator.next()
                if (!isOlderThan(candidate, nowMs, maxAgeMs)) continue
                delete(candidate.cacheId)
                deletedIds += candidate.cacheId
                iterator.remove()
                deleted++
            }
        }

        if (policy.maxFiles < Int.MAX_VALUE && deleted < policy.maxDeletesPerRun) {
            var eligibleCount = metadata.listAll()
                .count { it.cacheId !in deletedIds && it.isEligibleForPolicy(policy) }
            val iterator = candidates.iterator()
            while (eligibleCount > policy.maxFiles && iterator.hasNext() && deleted < policy.maxDeletesPerRun) {
                val candidate = iterator.next()
                delete(candidate.cacheId)
                deletedIds += candidate.cacheId
                iterator.remove()
                eligibleCount--
                deleted++
            }
        }

        var totalBytes = metadata.listAll()
            .filter { it.cacheId !in deletedIds && it.isEligibleForPolicy(policy) }
            .sumOf { it.totalBytes }
        if (totalBytes <= policy.maxBytes) return deleted
        for (candidate in candidates) {
            if (deleted >= policy.maxDeletesPerRun) break
            if (totalBytes <= policy.maxBytes) break
            delete(candidate.cacheId)
            deletedIds += candidate.cacheId
            totalBytes -= candidate.totalBytes
            deleted++
        }
        return deleted
    }

    suspend fun trimChat(
        chatId: String,
        policy: AttachmentRetentionPolicy,
        nowMs: Long = currentTimeMillis(),
        nowIso: String = currentIsoTimestamp(),
    ): Int {
        require(chatId.isNotBlank()) { "chatId is required" }
        var deleted = 0
        val allChatRecords = metadata.listForChat(chatId)
        allChatRecords
            .filter { record -> !isTransferProtected(record) && record.expiresAt?.let { it <= nowIso } == true }
            .forEach { record ->
                if (deleted >= policy.maxDeletesPerRun) return deleted
                delete(record.cacheId)
                deleted++
            }

        val deletedIds = mutableSetOf<String>()
        val candidates = metadata.listForChat(chatId)
            .filter { it.isEligibleForPolicy(policy) }
            .filterNot { isImmune(it, nowMs, policy.immunityMs) }
            .sortedWith(
                compareBy<LocalAttachmentRecord> { record ->
                    parseIsoTimestampMillis(record.lastAccessedAt ?: record.createdAt) ?: Long.MAX_VALUE
                }.thenBy { record ->
                    parseIsoTimestampMillis(record.createdAt) ?: Long.MAX_VALUE
                },
            )
            .toMutableList()

        policy.maxAgeMs?.let { maxAgeMs ->
            val iterator = candidates.iterator()
            while (iterator.hasNext() && deleted < policy.maxDeletesPerRun) {
                val candidate = iterator.next()
                if (!isOlderThan(candidate, nowMs, maxAgeMs)) continue
                delete(candidate.cacheId)
                deletedIds += candidate.cacheId
                iterator.remove()
                deleted++
            }
        }

        if (policy.maxFiles < Int.MAX_VALUE && deleted < policy.maxDeletesPerRun) {
            var eligibleCount = metadata.listForChat(chatId)
                .count { it.cacheId !in deletedIds && it.isEligibleForPolicy(policy) }
            val iterator = candidates.iterator()
            while (eligibleCount > policy.maxFiles && iterator.hasNext() && deleted < policy.maxDeletesPerRun) {
                val candidate = iterator.next()
                delete(candidate.cacheId)
                deletedIds += candidate.cacheId
                iterator.remove()
                eligibleCount--
                deleted++
            }
        }

        var totalBytes = metadata.listForChat(chatId)
            .filter { it.cacheId !in deletedIds && it.isEligibleForPolicy(policy) }
            .sumOf { it.totalBytes }
        if (totalBytes <= policy.maxBytes) return deleted
        for (candidate in candidates) {
            if (deleted >= policy.maxDeletesPerRun) break
            if (totalBytes <= policy.maxBytes) break
            delete(candidate.cacheId)
            deletedIds += candidate.cacheId
            totalBytes -= candidate.totalBytes
            deleted++
        }
        return deleted
    }

    private fun isImmune(record: LocalAttachmentRecord, nowMs: Long, immunityMs: Long): Boolean {
        val createdMs = parseIsoTimestampMillis(record.createdAt) ?: return false
        return nowMs - createdMs < immunityMs
    }

    private fun isOlderThan(record: LocalAttachmentRecord, nowMs: Long, maxAgeMs: Long): Boolean {
        val lastUsedMs = parseIsoTimestampMillis(record.lastAccessedAt ?: record.createdAt)
            ?: parseIsoTimestampMillis(record.createdAt)
            ?: return false
        return nowMs - lastUsedMs > maxAgeMs
    }

    private fun isTransferProtected(record: LocalAttachmentRecord): Boolean =
        record.state == AttachmentTransferState.PENDING ||
            record.state == AttachmentTransferState.UPLOADING ||
            record.state == AttachmentTransferState.DOWNLOADING ||
            record.state == AttachmentTransferState.PINNED

    private fun LocalAttachmentRecord.isEligibleForPolicy(policy: AttachmentRetentionPolicy): Boolean {
        if (isTransferProtected(this)) return false
        if (chatId in policy.excludedChatIds) return false
        if (policy.mimeTypePrefixes.isEmpty()) return true
        val normalized = mimeType?.lowercase() ?: return false
        return policy.mimeTypePrefixes.any { prefix -> normalized.startsWith(prefix.lowercase()) }
    }

    private val LocalAttachmentRecord.totalBytes: Long
        get() = encryptedSize + thumbnailSize
}

fun attachmentCacheIdForOutgoing(pendingId: String): String {
    require(pendingId.isNotBlank()) { "pendingId is required" }
    return "out-${sha256Hex(pendingId.encodeToByteArray()).take(32)}"
}

fun attachmentCacheIdForRemote(attachmentId: String): String {
    require(attachmentId.isNotBlank()) { "attachmentId is required" }
    return "remote-${sha256Hex(attachmentId.encodeToByteArray()).take(32)}"
}
