// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

data class ValidatedAttachmentMedia(
    val fileName: String?,
    val mimeType: String,
    val plaintextSize: Long,
    val canPreviewImage: Boolean,
)

object AttachmentMediaValidator {
    const val MAX_ATTACHMENT_BYTES = 100L * 1024L * 1024L
    const val MAX_THUMBNAIL_SOURCE_BYTES = 25L * 1024L * 1024L
    private const val MAX_FILE_NAME_CHARS = 180
    private val HEIC_BRANDS = setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1")

    fun validateOutgoing(
        bytes: ByteArray,
        fileName: String?,
        mimeType: String?,
    ): ValidatedAttachmentMedia {
        require(bytes.isNotEmpty()) { "attachment body is required" }
        require(bytes.size.toLong() <= MAX_ATTACHMENT_BYTES) { "attachment is too large" }
        val safeName = sanitizeFileName(fileName)
        val normalizedMime = normalizeMimeType(mimeType, safeName)
        return ValidatedAttachmentMedia(
            fileName = safeName,
            mimeType = normalizedMime,
            plaintextSize = bytes.size.toLong(),
            canPreviewImage = canPreviewImage(bytes, normalizedMime),
        )
    }

    fun validateOutgoingMetadata(
        plaintextSize: Long,
        fileName: String?,
        mimeType: String?,
    ): ValidatedAttachmentMedia {
        require(plaintextSize > 0) { "attachment body is required" }
        require(plaintextSize <= MAX_ATTACHMENT_BYTES) { "attachment is too large" }
        val safeName = sanitizeFileName(fileName)
        val normalizedMime = normalizeMimeType(mimeType, safeName)
        return ValidatedAttachmentMedia(
            fileName = safeName,
            mimeType = normalizedMime,
            plaintextSize = plaintextSize,
            canPreviewImage = false,
        )
    }

    fun validatePlaintextForPreview(
        bytes: ByteArray,
        fileName: String?,
        mimeType: String?,
        expectedPlaintextSize: Long? = null,
    ): ValidatedAttachmentMedia? {
        if (bytes.isEmpty()) return null
        if (bytes.size.toLong() > MAX_ATTACHMENT_BYTES) return null
        if (expectedPlaintextSize != null && expectedPlaintextSize > 0 && expectedPlaintextSize != bytes.size.toLong()) {
            return null
        }
        val safeName = runCatching { sanitizeFileName(fileName) }.getOrNull()
        val normalizedMime = normalizeMimeType(mimeType, safeName)
        val previewMime = when {
            canPreviewImage(bytes, normalizedMime) -> normalizedMime
            else -> sniffSupportedImageMime(bytes) ?: normalizedMime
        }
        val canPreview = canPreviewImage(bytes, previewMime)
        return ValidatedAttachmentMedia(
            fileName = safeName,
            mimeType = previewMime,
            plaintextSize = bytes.size.toLong(),
            canPreviewImage = canPreview,
        )
    }

    fun normalizeMimeType(mimeType: String?, fileName: String?): String {
        val normalized = normalizeMimeHeader(mimeType)
        if (
            normalized != null &&
            normalized != "application/octet-stream" &&
            normalized.none { it.isWhitespace() }
        ) return normalized
        val extension = fileName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt", "md", "log" -> "text/plain"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            else -> "application/octet-stream"
        }
    }

    fun isSupportedImageMime(mimeType: String?): Boolean =
        when (normalizeMimeHeader(mimeType)) {
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/heic",
            "image/heif",
            "image/gif",
            "image/bmp"
            -> true
            else -> false
        }

    fun canPreviewImage(bytes: ByteArray, mimeType: String?): Boolean {
        if (bytes.isEmpty()) return false
        if (bytes.size.toLong() > MAX_THUMBNAIL_SOURCE_BYTES) return false
        val normalized = normalizeMimeHeader(mimeType) ?: return false
        if (!isSupportedImageMime(normalized)) return false
        val sniffed = sniffSupportedImageMime(bytes) ?: return false
        return sniffed == normalized || (normalized == "image/heif" && sniffed == "image/heic")
    }

    fun sniffSupportedImageMime(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        if (bytes.size.toLong() > MAX_THUMBNAIL_SOURCE_BYTES) return null
        return when {
            startsWith(bytes, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            startsWith(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            startsWithAscii(bytes, "GIF87a") || startsWithAscii(bytes, "GIF89a") -> "image/gif"
            bytes.size >= 12 &&
                startsWithAscii(bytes, "RIFF") &&
                bytes[8] == 'W'.code.toByte() &&
                bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() &&
                bytes[11] == 'P'.code.toByte() -> "image/webp"
            startsWithAscii(bytes, "BM") -> "image/bmp"
            hasHeicBrand(bytes) -> "image/heic"
            else -> null
        }
    }

    private fun normalizeMimeHeader(mimeType: String?): String? =
        mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

    private fun sanitizeFileName(fileName: String?): String? {
        val trimmed = fileName?.trim()?.takeIf { it.isNotBlank() } ?: return null
        require(trimmed.length <= MAX_FILE_NAME_CHARS) { "attachment file name is too long" }
        require(trimmed.none { it.code < 32 || it == '/' || it == '\\' || it == '\u0000' }) {
            "attachment file name contains unsafe characters"
        }
        require(trimmed != "." && trimmed != "..") { "attachment file name is unsafe" }
        return trimmed
    }

    private fun startsWith(bytes: ByteArray, vararg signature: Int): Boolean {
        if (bytes.size < signature.size) return false
        return signature.indices.all { index -> bytes[index] == signature[index].toByte() }
    }

    private fun startsWithAscii(bytes: ByteArray, value: String): Boolean {
        if (bytes.size < value.length) return false
        return value.indices.all { index -> bytes[index] == value[index].code.toByte() }
    }

    private fun hasHeicBrand(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (bytes[4] != 'f'.code.toByte() || bytes[5] != 't'.code.toByte()) return false
        if (bytes[6] != 'y'.code.toByte() || bytes[7] != 'p'.code.toByte()) return false
        val brand = buildString(4) {
            append(bytes[8].toInt().toChar())
            append(bytes[9].toInt().toChar())
            append(bytes[10].toInt().toChar())
            append(bytes[11].toInt().toChar())
        }
        return brand in HEIC_BRANDS
    }
}
