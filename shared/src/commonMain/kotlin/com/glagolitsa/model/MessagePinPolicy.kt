// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

/**
 * Stable ids for local pinned-message refs.
 * Pure rules; persistence stays in repository/storage.
 */
object MessagePinPolicy {
    fun referenceId(message: Message): String =
        message.id.takeIf { it.isNotBlank() } ?: message.pending_id.orEmpty()

    fun candidateIds(message: Message): Set<String> =
        setOfNotNull(
            message.id.takeIf { it.isNotBlank() },
            message.pending_id?.takeIf { it.isNotBlank() },
        )

    fun matches(message: Message, pinnedMessageId: String): Boolean =
        pinnedMessageId in candidateIds(message)
}
