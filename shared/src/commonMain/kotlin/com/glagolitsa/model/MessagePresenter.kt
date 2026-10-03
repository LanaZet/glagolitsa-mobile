// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Подпись отправителя в пузыре сообщения.
 *
 * @param currentUserId id текущего пользователя
 * @param dmPartnerName имя собеседника в DM (из [Chat.title]) — сервер персонализирует его
 * @param senderName имя отправителя в групповом чате (из кэша repository)
 */
fun Message.senderLabel(
    currentUserId: String?,
    dmPartnerName: String? = null,
    senderName: String? = null,
): String {
    if (sender_id == currentUserId) return "Вы"
    dmPartnerName?.takeIf { it.isNotBlank() }?.let { return it }
    senderName?.takeIf { it.isNotBlank() }?.let { return it }
    return "Участник"
}