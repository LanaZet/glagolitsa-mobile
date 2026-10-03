// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.model.CallSession
import com.glagolitsa.model.CallType
import com.glagolitsa.model.callJoinLink
import com.glagolitsa.model.isTerminal

class GroupCallWorkflow(
    private val repository: MessengerRepository,
) {
    suspend fun startOrJoin(
        chatId: String,
        chatTitle: String,
        existingCall: CallSession? = null,
        callType: String = CallType.AUDIO,
    ): CallSession {
        val active = existingCall?.takeUnless { it.isTerminal() }
        if (active != null) {
            return repository.calls.joinGroupCall(active.id, chatTitle)
        }
        return startAndShare(chatId, chatTitle, callType)
    }

    suspend fun startAndShare(
        chatId: String,
        chatTitle: String,
        callType: String = CallType.AUDIO,
    ): CallSession {
        val started = repository.calls.startGroupCall(chatId, chatTitle, callType = callType)
        runCatching {
            repository.sendMessage(chatId, groupCallMessage(started.id))
        }
        return started
    }

    suspend fun join(callId: String, chatTitle: String): CallSession =
        repository.calls.joinGroupCall(callId, chatTitle)

    suspend fun activeCallForChat(chatId: String): CallSession? =
        repository.calls.activeCallForChat(chatId)
}

fun groupCallMessage(callId: String): String =
    "Звонок в группе: ${callJoinLink(callId)}"
