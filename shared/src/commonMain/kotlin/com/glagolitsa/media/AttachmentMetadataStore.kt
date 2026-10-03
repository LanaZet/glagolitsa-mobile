// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import com.glagolitsa.db.LocalDataStore

class AttachmentMetadataStore(
    private val local: LocalDataStore,
) {
    suspend fun upsert(record: LocalAttachmentRecord) = local.upsertLocalAttachment(record)
    suspend fun find(cacheId: String): LocalAttachmentRecord? = local.findLocalAttachment(cacheId)
    suspend fun findByAttachmentId(attachmentId: String): LocalAttachmentRecord? =
        local.findLocalAttachmentByAttachmentId(attachmentId)
    suspend fun listForChat(chatId: String): List<LocalAttachmentRecord> =
        local.listLocalAttachmentsForChat(chatId)
    suspend fun listAll(): List<LocalAttachmentRecord> = local.listAllLocalAttachments()
    suspend fun markState(cacheId: String, state: String, error: String? = null, accessedAt: String) =
        local.updateLocalAttachmentState(cacheId, state, error, accessedAt)
    suspend fun markUploaded(cacheId: String, attachmentId: String, accessedAt: String) =
        local.updateLocalAttachmentRemoteId(cacheId, attachmentId, AttachmentTransferState.UPLOADED, accessedAt)
    suspend fun linkMessage(cacheId: String, messageId: String, accessedAt: String) =
        local.updateLocalAttachmentMessageId(cacheId, messageId, accessedAt)
    suspend fun relinkMessage(fromMessageId: String, toMessageId: String, accessedAt: String) =
        local.relinkLocalAttachmentMessageId(fromMessageId, toMessageId, accessedAt)
    suspend fun repairMessageLinksForChat(chatId: String, accessedAt: String) =
        local.repairLocalAttachmentMessageLinksForChat(chatId, accessedAt)
    suspend fun setThumbnail(
        cacheId: String,
        thumbnailPath: String,
        thumbnailKey: String,
        thumbnailSize: Long,
        accessedAt: String,
    ) = local.updateLocalAttachmentThumbnail(cacheId, thumbnailPath, thumbnailKey, thumbnailSize, accessedAt)
    suspend fun touch(cacheId: String, accessedAt: String) = local.touchLocalAttachment(cacheId, accessedAt)
    suspend fun delete(cacheId: String) = local.deleteLocalAttachmentRecord(cacheId)
    suspend fun deleteForChat(chatId: String) = local.deleteLocalAttachmentsForChat(chatId)
    suspend fun expired(nowIso: String): List<LocalAttachmentRecord> = local.expiredLocalAttachments(nowIso)
    suspend fun trimCandidates(limit: Int): List<LocalAttachmentRecord> = local.localAttachmentTrimCandidates(limit)
    suspend fun totalBytes(): Long = local.sumLocalAttachmentBytes()
}
