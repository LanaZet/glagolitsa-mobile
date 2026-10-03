// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Mattermost-style conversation removal:
 *  - DM → hide locally (server DM is not deleted)
 *  - group/channel owner → permanent delete for everyone
 *  - other members → leave (drop membership)
 */
object ChatDeletePolicy {
    enum class Action {
        HideLocally,
        Leave,
        PermanentDelete,
    }

    fun action(chat: Chat, currentUserId: String?, membershipRole: String? = null): Action {
        if (!chat.isGroup && !chat.isChannel) return Action.HideLocally
        val me = currentUserId?.trim().orEmpty()
        val owner = chat.creator_id?.trim().orEmpty()
        val isOwner = (me.isNotEmpty() && owner.isNotEmpty() && me == owner) ||
            membershipRole.equals(GroupRoles.OWNER, ignoreCase = true)
        if (isOwner) {
            return Action.PermanentDelete
        }
        return Action.Leave
    }

    fun confirmTitle(chat: Chat, action: Action): String = when (action) {
        Action.PermanentDelete -> if (chat.isChannel) {
            "Удалить канал?"
        } else {
            "Удалить группу?"
        }
        Action.Leave -> if (chat.isChannel) {
            "Выйти из канала?"
        } else {
            "Выйти из группы?"
        }
        Action.HideLocally -> "Удалить чат из списка?"
    }

    fun confirmBody(chat: Chat, action: Action): String = when (action) {
        Action.PermanentDelete ->
            "«${chat.title}» будет удалён у всех: участники и сообщения на сервере тоже. Это нельзя отменить."
        Action.Leave ->
            "Вы выйдете из «${chat.title}». Чат пропадёт из вашего списка, но останется у остальных."
        Action.HideLocally ->
            "Чат «${chat.title}» будет удалён из вашего списка."
    }

    fun confirmButton(action: Action): String = when (action) {
        Action.PermanentDelete -> "Удалить для всех"
        Action.Leave -> "Выйти"
        Action.HideLocally -> "Удалить"
    }
}
