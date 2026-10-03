// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.crypto.encryptAttachmentStreaming
import com.glagolitsa.media.AttachmentMediaValidator
import com.glagolitsa.media.PlatformMediaFileStore
import com.glagolitsa.media.resolveAttachmentKind
import platform.Foundation.NSUUID

internal const val IOS_MAX_ATTACHMENT_BYTES = AttachmentMediaValidator.MAX_ATTACHMENT_BYTES

internal val iosAllowedMimeTypes = setOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/heic",
    "image/heif",
    "video/mp4",
    "video/quicktime",
    "video/webm",
    "application/pdf",
    "application/zip",
    "application/x-zip-compressed",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "audio/mpeg",
    "audio/mp4",
    "audio/aac",
    "audio/wav",
    "audio/ogg",
    "text/plain",
)

internal val iosAllowedExtensions = setOf(
    "jpg", "jpeg", "png", "webp", "heic", "heif",
    "mp4", "mov", "webm",
    "pdf", "zip", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "mp3", "m4a", "aac", "wav", "ogg", "txt",
)

internal suspend fun iosBuildPickedAttachment(
    bytes: ByteArray,
    fileName: String?,
    mimeType: String?,
): PickedAttachment {
    if (bytes.isEmpty()) {
        return PickedAttachment(
            fileName = fileName,
            mimeType = mimeType,
            errorMessage = "Пустой файл нельзя отправить",
        )
    }
    if (bytes.size.toLong() > IOS_MAX_ATTACHMENT_BYTES) {
        return PickedAttachment(
            fileName = fileName,
            mimeType = mimeType,
            errorMessage = "Файл слишком большой (максимум 100 МБ)",
        )
    }
    val extension = fileName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
    val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase()
    if (!iosIsAllowedType(normalizedMime, extension)) {
        return PickedAttachment(
            fileName = fileName,
            mimeType = mimeType,
            errorMessage = "Неподдерживаемый тип файла",
        )
    }
    if (!iosMatchesMagic(bytes, normalizedMime, extension)) {
        return PickedAttachment(
            fileName = fileName,
            mimeType = mimeType,
            errorMessage = "Файл не прошел проверку безопасности",
        )
    }
    return runCatching {
        val encrypted = encryptAttachmentStreaming(bytes)
        val cacheId = "picker-${NSUUID().UUIDString}"
        val path = PlatformMediaFileStore().writeEncrypted(cacheId, encrypted.ciphertext)
        PickedAttachment(
            fileName = fileName,
            mimeType = normalizedMime ?: mimeType,
            encryptedSpool = PickedEncryptedAttachmentSpool(
                path = path,
                fileKey = encrypted.fileKey,
                encryptedSize = encrypted.ciphertext.size.toLong(),
                plaintextSize = bytes.size.toLong(),
            ),
            kind = resolveAttachmentKind(normalizedMime, fileName),
        )
    }.getOrElse {
        PickedAttachment(
            fileName = fileName,
            mimeType = mimeType,
            errorMessage = "Не удалось подготовить файл",
        )
    }
}

internal fun iosGuessMime(fileName: String?, fallback: String?): String? {
    val ext = fileName?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
    return when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "txt" -> "text/plain"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        else -> fallback
    }
}

private fun iosIsAllowedType(mimeType: String?, extension: String?): Boolean {
    if (mimeType != null && mimeType !in iosAllowedMimeTypes) return false
    if (extension != null && extension !in iosAllowedExtensions) return false
    return mimeType != null || extension != null
}

private fun iosMatchesMagic(bytes: ByteArray, mimeType: String?, extension: String?): Boolean {
    val header = bytes.copyOfRange(0, minOf(bytes.size, 16))
    val pdf = header.size >= 4 &&
        header[0] == 0x25.toByte() && header[1] == 0x50.toByte() &&
        header[2] == 0x44.toByte() && header[3] == 0x46.toByte()
    val zip = header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
        ((header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) ||
            (header[2] == 0x05.toByte() && header[3] == 0x06.toByte()) ||
            (header[2] == 0x07.toByte() && header[3] == 0x08.toByte()))
    val compoundOffice = header.size >= 8 && header.take(8) == listOf(
        0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1,
    ).map(Int::toByte)
    val mp3 = header.size >= 3 &&
        ((header[0] == 0x49.toByte() && header[1] == 0x44.toByte() && header[2] == 0x33.toByte()) ||
            (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0))
    val wav = header.size >= 12 &&
        header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
        header[2] == 0x46.toByte() && header[3] == 0x46.toByte()
    val ogg = header.size >= 4 &&
        header[0] == 0x4F.toByte() && header[1] == 0x67.toByte() &&
        header[2] == 0x67.toByte() && header[3] == 0x53.toByte()
    val mp4 = header.size >= 8 &&
        header[4] == 0x66.toByte() && header[5] == 0x74.toByte() &&
        header[6] == 0x79.toByte() && header[7] == 0x70.toByte()
    val webm = header.size >= 4 &&
        header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
        header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
    val expected = (mimeType ?: extension.orEmpty()).lowercase()
    val detectedImageMime = iosDetectImageMime(bytes)
    return when {
        expected.contains("jpeg") || expected.contains("jpg") -> detectedImageMime == "image/jpeg"
        expected.contains("png") -> detectedImageMime == "image/png"
        expected.contains("webp") -> detectedImageMime == "image/webp"
        expected.contains("heic") || expected.contains("heif") -> detectedImageMime == "image/heic"
        expected.contains("pdf") -> pdf
        expected.contains("zip") || expected.contains("docx") ||
            expected.contains("xlsx") || expected.contains("pptx") -> zip
        expected.contains("msword") || expected == "doc" || expected == "xls" || expected == "ppt" -> compoundOffice
        expected.contains("mp4") || expected.contains("quicktime") ||
            expected.contains("mov") || expected.contains("m4a") || expected.contains("aac") -> mp4 || mp3
        expected.contains("webm") -> webm
        expected.contains("mpeg") || expected.contains("mp3") -> mp3
        expected.contains("wav") -> wav
        expected.contains("ogg") -> ogg
        expected.contains("txt") || expected.contains("plain") -> iosLooksLikeText(bytes)
        else -> false
    }
}

internal fun iosDetectImageMime(bytes: ByteArray): String? {
    if (bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
    ) return "image/jpeg"
    if (bytes.size >= 8 && bytes.take(8) == listOf(
            0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        ).map(Int::toByte)
    ) return "image/png"
    if (bytes.size >= 12 && bytes.asciiAt(0, 4) == "RIFF" && bytes.asciiAt(8, 4) == "WEBP") {
        return "image/webp"
    }
    if (bytes.size >= 12 && bytes.asciiAt(4, 4) == "ftyp" &&
        bytes.asciiAt(8, 4) in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1")
    ) return "image/heic"
    return null
}

private fun ByteArray.asciiAt(offset: Int, length: Int): String =
    copyOfRange(offset, offset + length).decodeToString()

private fun iosLooksLikeText(bytes: ByteArray): Boolean = runCatching {
    bytes.decodeToString(throwOnInvalidSequence = true).all { char ->
        char == '\n' || char == '\r' || char == '\t' || !char.isISOControl()
    }
}.getOrDefault(false)
