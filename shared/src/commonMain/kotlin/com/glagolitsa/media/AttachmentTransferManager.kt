// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import com.glagolitsa.api.ApiClient
import com.glagolitsa.api.ApiException
import com.glagolitsa.currentIsoTimestamp
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

class AttachmentTransferManager(
    private val api: ApiClient,
    private val cache: AttachmentCache,
    private val nowIso: () -> String = ::currentIsoTimestamp,
) {
    suspend fun uploadOutgoing(
        token: String,
        cacheId: String?,
        legacyEncryptedBytes: ByteArray?,
        kind: AttachmentKind = AttachmentKind.DOCUMENT,
        mimeType: String? = null,
    ): String {
        val cachedBlob = cacheId?.let { cache.openEncrypted(it) }
        val legacyBlob = if (cachedBlob == null) {
            legacyEncryptedBytes ?: error("Attachment payload missing")
        } else {
            null
        }
        return try {
            cacheId?.let { cache.markUploading(it) }
            val fileId = try {
                val slot = api.createEncryptedMediaSlot(
                    token = token,
                    kind = kind.toMediaSlotKind(),
                    mimeType = mimeType ?: "application/octet-stream",
                )
                if (cachedBlob != null) {
                    api.uploadEncryptedMedia(
                        token = token,
                        fileId = slot.file_id,
                        encryptedSize = cachedBlob.encryptedSize,
                        readChunk = { offset, maxBytes ->
                            cache.readEncryptedChunk(cachedBlob, offset, maxBytes) ?: ByteArray(0)
                        },
                    )
                } else {
                    api.uploadEncryptedMedia(token, slot.file_id, legacyBlob ?: error("Attachment payload missing"))
                }
                slot.file_id
            } catch (e: Throwable) {
                if (!e.isMediaFilesEndpointUnsupported()) throw e
                val slot = api.createAttachment(token)
                if (cachedBlob != null) {
                    api.uploadAttachment(
                        token = token,
                        attachmentId = slot.attachment_id,
                        encryptedSize = cachedBlob.encryptedSize,
                        readChunk = { offset, maxBytes ->
                            cache.readEncryptedChunk(cachedBlob, offset, maxBytes) ?: ByteArray(0)
                        },
                    )
                } else {
                    api.uploadAttachment(token, slot.attachment_id, legacyBlob ?: error("Attachment payload missing"))
                }
                slot.attachment_id
            }
            cacheId?.let { cache.markUploaded(it, fileId) }
            fileId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            cacheId?.let { id ->
                runCatching { cache.markFailed(id, e.message ?: e::class.simpleName) }
            }
            throw e
        }
    }

    suspend fun loadOrDownload(
        token: String,
        attachmentId: String,
        cacheId: String,
        chatId: String,
        ownerAccountId: String,
        messageId: String,
        fileName: String?,
        mimeType: String?,
        plaintextSize: Long,
        kind: AttachmentKind = resolveAttachmentKind(mimeType, fileName),
        width: Int? = null,
        height: Int? = null,
        durationMs: Long? = null,
        waveform: String? = null,
    ): ByteArray {
        cache.readEncrypted(cacheId)?.let { return it }
        cache.metadata.findByAttachmentId(attachmentId)
            ?.let { existing -> cache.readEncrypted(existing.cacheId) }
            ?.let { return it }

        val createdAt = nowIso()
        cache.metadata.upsert(
            LocalAttachmentRecord(
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
                plaintextSize = plaintextSize,
                state = AttachmentTransferState.DOWNLOADING,
                lastAccessedAt = createdAt,
                createdAt = createdAt,
            ),
        )

        return try {
            val encryptedBlob = api.downloadAttachment(token, attachmentId)
            cache.putDownloadedEncrypted(
                cacheId = cacheId,
                attachmentId = attachmentId,
                encryptedBytes = encryptedBlob,
                chatId = chatId,
                ownerAccountId = ownerAccountId,
                messageId = messageId,
                fileName = fileName,
                mimeType = mimeType,
                plaintextSize = plaintextSize,
                kind = kind,
                width = width,
                height = height,
                durationMs = durationMs,
                waveform = waveform,
            )
            encryptedBlob
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            cache.markFailed(cacheId, e.message ?: e::class.simpleName)
            throw e
        }
    }
}

private fun AttachmentKind.toMediaSlotKind(): String =
    when (this) {
        AttachmentKind.IMAGE -> "photo"
        AttachmentKind.VIDEO -> "video"
        AttachmentKind.VOICE -> "voice"
        AttachmentKind.AUDIO -> "audio"
        AttachmentKind.DOCUMENT,
        AttachmentKind.UNKNOWN,
        -> "document"
    }

private fun Throwable.isMediaFilesEndpointUnsupported(): Boolean =
    this is ApiException &&
        (status == HttpStatusCode.NotFound || status == HttpStatusCode.MethodNotAllowed)
