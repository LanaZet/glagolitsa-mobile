// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.media.AttachmentKind
import com.glagolitsa.media.AttachmentMediaValidator
import com.glagolitsa.media.resolveAttachmentKind
import com.glagolitsa.model.ChatMediaOpenPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AttachmentPreview(
    val messageId: String,
    val bytes: ByteArray,
    val thumbnailBytes: ByteArray? = null,
    val fileName: String?,
    val mimeType: String?,
    val kind: AttachmentKind = resolveAttachmentKind(mimeType, fileName),
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val waveform: List<Int> = emptyList(),
) {
    val displayBytes: ByteArray
        get() = thumbnailBytes ?: bytes

    val isImage: Boolean = kind == AttachmentKind.IMAGE
    val isVideo: Boolean = kind == AttachmentKind.VIDEO
    val isAnimatedGif: Boolean
        get() = ChatMediaOpenPolicy.isAnimatedGif(mimeType, bytes, thumbnailBytes)
    val isVoice: Boolean = kind == AttachmentKind.VOICE
    val isAudio: Boolean = kind == AttachmentKind.AUDIO
    val isDocument: Boolean = kind == AttachmentKind.DOCUMENT || kind == AttachmentKind.UNKNOWN

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttachmentPreview) return false
        return messageId == other.messageId &&
            bytes.contentEquals(other.bytes) &&
            ((thumbnailBytes == null && other.thumbnailBytes == null) ||
                (thumbnailBytes != null && other.thumbnailBytes != null && thumbnailBytes.contentEquals(other.thumbnailBytes))) &&
            fileName == other.fileName &&
            mimeType == other.mimeType &&
            kind == other.kind &&
            width == other.width &&
            height == other.height &&
            durationMs == other.durationMs &&
            waveform == other.waveform
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + (thumbnailBytes?.contentHashCode() ?: 0)
        result = 31 * result + (fileName?.hashCode() ?: 0)
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        result = 31 * result + kind.hashCode()
        result = 31 * result + (width ?: 0)
        result = 31 * result + (height ?: 0)
        result = 31 * result + (durationMs?.hashCode() ?: 0)
        result = 31 * result + waveform.hashCode()
        return result
    }
}

internal class AttachmentPreviewStore {
    private val _state = MutableStateFlow<Map<String, AttachmentPreview>>(emptyMap())
    val state: StateFlow<Map<String, AttachmentPreview>> = _state.asStateFlow()

    fun cache(
        messageId: String,
        bytes: ByteArray,
        fileName: String?,
        mimeType: String?,
        kind: AttachmentKind? = null,
        thumbnailBytes: ByteArray? = null,
        width: Int? = null,
        height: Int? = null,
        durationMs: Long? = null,
        waveform: List<Int> = emptyList(),
    ) {
        val normalizedMimeType = normalizeAttachmentMimeType(mimeType, fileName)
        val sniffedImageMime = AttachmentMediaValidator.sniffSupportedImageMime(bytes)
        val resolvedKind = kind?.takeUnless { it == AttachmentKind.UNKNOWN }
            ?: when {
                sniffedImageMime != null -> AttachmentKind.IMAGE
                else -> resolveAttachmentKind(normalizedMimeType, fileName)
            }
        val (resolvedMimeType, previewBytes, previewThumbnail) = when (resolvedKind) {
            AttachmentKind.IMAGE -> {
                val imageMime = when {
                    AttachmentMediaValidator.canPreviewImage(bytes, normalizedMimeType) -> normalizedMimeType
                    else -> sniffedImageMime ?: return
                }
                Triple(imageMime, bytes, thumbnailBytes)
            }
            AttachmentKind.VIDEO -> {
                val playbackBytes = bytes.takeIf { it.isNotEmpty() } ?: ByteArray(0)
                Triple(normalizedMimeType, playbackBytes, thumbnailBytes)
            }
            AttachmentKind.AUDIO,
            AttachmentKind.VOICE,
            -> {
                Triple(normalizedMimeType, bytes, thumbnailBytes)
            }
            AttachmentKind.DOCUMENT,
            AttachmentKind.UNKNOWN,
            -> {
                Triple(normalizedMimeType, ByteArray(0), thumbnailBytes)
            }
        }
        val preview = AttachmentPreview(
            messageId = messageId,
            bytes = previewBytes,
            thumbnailBytes = previewThumbnail,
            fileName = fileName,
            mimeType = resolvedMimeType,
            kind = resolvedKind,
            width = width,
            height = height,
            durationMs = durationMs,
            waveform = waveform,
        )
        _state.value = _state.value + (messageId to preview)
    }

    fun remove(messageId: String) {
        _state.value = _state.value - messageId
    }

    fun transfer(fromMessageId: String, toMessageId: String) {
        if (fromMessageId == toMessageId) return
        val existing = _state.value[fromMessageId] ?: return
        _state.value = _state.value - fromMessageId + (
            toMessageId to existing.copy(messageId = toMessageId)
        )
    }
}

internal fun isImageAttachment(mimeType: String?, fileName: String?): Boolean =
    AttachmentMediaValidator.isSupportedImageMime(normalizeAttachmentMimeType(mimeType, fileName))

internal fun normalizeAttachmentMimeType(mimeType: String?, fileName: String?): String =
    AttachmentMediaValidator.normalizeMimeType(mimeType, fileName)
