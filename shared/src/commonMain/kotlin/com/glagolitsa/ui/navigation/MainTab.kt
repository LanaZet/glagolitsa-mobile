// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.navigation

enum class MainTab {
    Chats,
    Contacts,
    Calls,
    Settings,
    ;

    val label: String
        get() = when (this) {
            Chats -> "Чаты"
            Contacts -> "Контакты"
            Calls -> "Звонки"
            Settings -> "Настройки"
        }

    companion object {
        val defaultOrder: List<MainTab> = entries.toList()

        private val legacyAliases = mapOf(
            "Personal" to Contacts,
            "Profile" to Settings,
        )

        fun parseOrder(raw: String?): List<MainTab> {
            if (raw.isNullOrBlank()) return defaultOrder

            val parsed = raw.split(',')
                .mapNotNull { entry ->
                    val trimmed = entry.trim()
                    entries.find { it.name == trimmed } ?: legacyAliases[trimmed]
                }

            return if (parsed.size == entries.size && parsed.toSet().size == entries.size) {
                parsed
            } else {
                defaultOrder
            }
        }

        fun encodeOrder(order: List<MainTab>): String = order.joinToString(",") { it.name }
    }
}