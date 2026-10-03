// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.media

import com.glagolitsa.repository.MessengerRepository

private const val CHAT_MEDIA_KEEP_KEY_PREFIX = "chat.media.keep."
private const val DAY_MS = 24L * 60L * 60L * 1000L

enum class ChatMediaKeepMode(
    val storageKey: String,
    val label: String,
    val maxAgeMs: Long?,
) {
    Default("default", "По умолчанию", null),
    SevenDays("7d", "7 дней", 7L * DAY_MS),
    ThirtyDays("30d", "30 дней", 30L * DAY_MS),
    Forever("forever", "Всегда", null);

    companion object {
        fun fromStorageKey(value: String): ChatMediaKeepMode =
            entries.firstOrNull { it.storageKey == value } ?: Default
    }
}

data class ChatMediaCachePolicy(
    val keepMode: ChatMediaKeepMode = ChatMediaKeepMode.Default,
) {
    fun retentionPolicyForChat(chatId: String): AttachmentRetentionPolicy =
        when (keepMode) {
            ChatMediaKeepMode.Forever -> AttachmentRetentionPolicy(
                maxBytes = Long.MAX_VALUE,
                excludedChatIds = setOf(chatId),
            )
            ChatMediaKeepMode.SevenDays,
            ChatMediaKeepMode.ThirtyDays,
            -> AttachmentRetentionPolicy(
                maxBytes = Long.MAX_VALUE,
                maxAgeMs = keepMode.maxAgeMs,
            )
            ChatMediaKeepMode.Default -> AttachmentRetentionPolicy(maxBytes = Long.MAX_VALUE)
        }
}

suspend fun MessengerRepository.loadChatMediaCachePolicy(chatId: String): ChatMediaCachePolicy =
    ChatMediaCachePolicy(
        keepMode = ChatMediaKeepMode.fromStorageKey(
            loadProfileSetting(CHAT_MEDIA_KEEP_KEY_PREFIX + chatId, ChatMediaKeepMode.Default.storageKey),
        ),
    )

suspend fun MessengerRepository.saveChatMediaCachePolicy(
    chatId: String,
    policy: ChatMediaCachePolicy,
) {
    saveProfileSetting(CHAT_MEDIA_KEEP_KEY_PREFIX + chatId, policy.keepMode.storageKey)
}

suspend fun MessengerRepository.loadForeverMediaCacheChatIds(): Set<String> =
    loadProfileSettingsByPrefix(CHAT_MEDIA_KEEP_KEY_PREFIX)
        .filterValues { value -> ChatMediaKeepMode.fromStorageKey(value) == ChatMediaKeepMode.Forever }
        .keys
        .mapNotNull { key -> key.removePrefix(CHAT_MEDIA_KEEP_KEY_PREFIX).takeIf { it.isNotBlank() } }
        .toSet()
