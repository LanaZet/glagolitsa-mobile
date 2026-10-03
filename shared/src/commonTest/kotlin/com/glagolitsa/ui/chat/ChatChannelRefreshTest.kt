// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import com.glagolitsa.jobs.OutboxProcessingResult
import com.glagolitsa.jobs.OutboxRecoveryResult
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ChatChannelRefreshTest {
    @Test
    fun dmRefresh_runsRecoveryOutboxSyncQueueAndFinalOutbox() = runTest {
        val ops = RecordingRefreshOperations(
            processOutboxResults = listOf(
                OutboxProcessingResult(attempted = 1, sent = 1),
                OutboxProcessingResult(),
            ),
        )

        runChatChannelRefresh(dmChat(), ops)

        assertEquals(
            listOf(
                "recover",
                "retry:chat-1",
                "outbox:20",
                "sync:chat-1",
                "queue:chat-1:true",
                "outbox:20",
            ),
            ops.calls,
        )
    }

    @Test
    fun groupRefresh_skipsDirectMessageQueue() = runTest {
        val ops = RecordingRefreshOperations()

        runChatChannelRefresh(Chat(id = "group-1", title = "Group", type = ChatType.GROUP), ops)

        assertFalse(ops.calls.any { it.startsWith("queue:") })
        assertEquals(
            listOf(
                "recover",
                "retry:group-1",
                "outbox:20",
                "sync:group-1",
            ),
            ops.calls,
        )
    }

    @Test
    fun outboxFailureMakesRefreshIncomplete() = runTest {
        val ops = RecordingRefreshOperations(
            retryResult = OutboxRecoveryResult(
                requeued = 1,
                outbox = OutboxProcessingResult(
                    attempted = 1,
                    transientFailures = 1,
                    lastError = "Failed to connect to /10.0.2.2:8080",
                ),
            ),
        )

        val error = assertFailsWith<ChatRefreshIncompleteException> {
            runChatChannelRefresh(dmChat(), ops)
        }

        assertEquals(CHAT_REFRESH_INCOMPLETE_MESSAGE, error.message)
    }

    @Test
    fun transientSyncFailureMakesRefreshIncomplete() = runTest {
        val ops = RecordingRefreshOperations(
            syncFailure = IllegalStateException("Request timeout has expired"),
        )

        val error = assertFailsWith<ChatRefreshIncompleteException> {
            runChatChannelRefresh(dmChat(), ops)
        }

        assertEquals(CHAT_REFRESH_INCOMPLETE_MESSAGE, error.message)
    }

    @Test
    fun authRecoveryFailureUsesAuthRefreshMessage() = runTest {
        val ops = RecordingRefreshOperations(recoverSessionResult = false)

        val error = assertFailsWith<ChatRefreshIncompleteException> {
            runChatChannelRefresh(dmChat(), ops)
        }

        assertEquals(CHAT_REFRESH_AUTH_MESSAGE, error.message)
        assertEquals(listOf("recover"), ops.calls)
    }

    private fun dmChat(): Chat = Chat(id = "chat-1", title = "Polo", type = ChatType.DIRECT)

    private class RecordingRefreshOperations(
        private val recoverSessionResult: Boolean = true,
        private val retryResult: OutboxRecoveryResult = OutboxRecoveryResult(),
        processOutboxResults: List<OutboxProcessingResult> = listOf(OutboxProcessingResult(), OutboxProcessingResult()),
        private val syncFailure: Throwable? = null,
        private val queueFailure: Throwable? = null,
        private val recoverAuthFailureResult: Boolean = false,
    ) : ChatChannelRefreshOperations {
        val calls = mutableListOf<String>()
        private val outboxResults = ArrayDeque(processOutboxResults)

        override suspend fun recoverSession(): Boolean {
            calls += "recover"
            return recoverSessionResult
        }

        override suspend fun retryUndelivered(chatId: String): OutboxRecoveryResult {
            calls += "retry:$chatId"
            return retryResult
        }

        override suspend fun processOutbox(maxJobs: Int): OutboxProcessingResult {
            calls += "outbox:$maxJobs"
            return outboxResults.removeFirstOrNull() ?: OutboxProcessingResult()
        }

        override suspend fun syncMessages(chatId: String) {
            calls += "sync:$chatId"
            syncFailure?.let { throw it }
        }

        override suspend fun processMessageQueue(chatId: String, force: Boolean) {
            calls += "queue:$chatId:$force"
            queueFailure?.let { throw it }
        }

        override suspend fun recoverAuthFailure(err: Throwable): Boolean = recoverAuthFailureResult

        override fun isTransientFailure(err: Throwable): Boolean =
            TransientNetworkErrors.isTransient(err)
    }
}
