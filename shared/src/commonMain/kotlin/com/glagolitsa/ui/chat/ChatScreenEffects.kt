// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import com.glagolitsa.model.Chat
import com.glagolitsa.model.Message
import com.glagolitsa.model.MessageRelationDraft
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.jobs.OutboxProcessingResult
import com.glagolitsa.jobs.OutboxRecoveryResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** Пока пользователь в приложении — outbox + relay queue (даже вне экрана чата). */
@Composable
fun AppInboxSyncEffect(
    token: String?,
    repository: MessengerRepository,
) {
    LaunchedEffect(token) {
        if (token == null) return@LaunchedEffect
        while (isActive) {
            runCatching {
                repository.maintainSession()
                repository.drainPendingSync()
            }
            delay(8_000)
        }
    }
}

/** Держит WebSocket на уровне приложения — не рвётся при навигации между экранами. */
@Composable
fun AppWebSocketEffect(
    token: String?,
    repository: MessengerRepository,
) {
    LaunchedEffect(token) {
        if (token != null) {
            repository.startWebSocket(this)
        } else {
            repository.stopWebSocket()
        }
    }
}

/** Подгружает историю при открытии чата и опрашивает relay-пока экран открыт. */
@Composable
fun ChatSyncEffect(
    chat: Chat,
    repository: MessengerRepository,
    onError: (String?) -> Unit,
) {
    LaunchedEffect(chat.id) {
        runCatching {
            repository.syncMessages(chat.id)
        }.onFailure { err -> repository.reportSyncFailure(err, onError) }

        // Дополнительный опрос сразу после push/WS-события (UI уже через observeMainMessages).
        val incomingJob = launch {
            repository.incomingMessages
                .filter { it.chat_id == chat.id }
                .collect {
                    if (!chat.isDirectMessage) return@collect
                    runCatching { repository.processMessageQueue(chat.id) }
                        .onFailure { err -> repository.reportSyncFailure(err, onError) }
                }
        }

        // WebSocket на локальном API/MIUI ненадёжен — опрашиваем relay, пока чат на экране.
        // One recovery pass when opening the chat (covers failed/stuck after offline).
        runCatching { repository.retryUndeliveredMessages(chatId = chat.id) }
            .onFailure { err -> repository.reportSyncFailure(err, onError) }

        try {
            while (isActive) {
                if (chat.isDirectMessage) {
                    runCatching {
                        repository.processOutbox(maxJobs = 10)
                        repository.processMessageQueue(chat.id, force = true)
                    }.onFailure { err -> repository.reportSyncFailure(err, onError) }
                } else {
                    runCatching { repository.processOutbox(maxJobs = 10) }
                        .onFailure { err -> repository.reportSyncFailure(err, onError) }
                }
                delay(2_000)
            }
        } finally {
            incomingJob.cancel()
        }
    }
}

/**
 * Прокручивает ленту к последнему сообщению, когда приходят новые.
 * Счётчик привязан к chatId — при смене чата сбрасывается.
 */
@Composable
fun ChatAutoScrollEffect(
    chatId: String,
    messages: List<Message>,
    listState: LazyListState,
    /** When set, scroll targets this timeline (day headers shift indices). */
    timeline: List<ChatTimelineItem>? = null,
    currentUserId: String? = null,
    reverseLayout: Boolean = true,
) {
    var lastSeenCount by remember(chatId) { mutableIntStateOf(0) }
    var lastSeenLastId by remember(chatId) { mutableStateOf<String?>(null) }

    LaunchedEffect(messages.size, messages.lastOrNull()?.id, timeline?.size, currentUserId) {
        val pinned = isPinnedToLatest(
            reverseLayout = reverseLayout,
            firstVisibleIndex = listState.firstVisibleItemIndex,
            firstVisibleOffset = listState.firstVisibleItemScrollOffset,
            canScrollForward = listState.canScrollForward,
        )
        val lastIsOwn = currentUserId != null && messages.lastOrNull()?.sender_id == currentUserId
        if (shouldAutoScrollToLatest(
                previousCount = lastSeenCount,
                previousLastId = lastSeenLastId,
                messages = messages,
                pinnedToBottom = pinned,
                lastMessageIsOwn = lastIsOwn,
            )
        ) {
            val visual = timeline?.let { visualTimelineItems(it, loadingOlder = false) }
            val target = if (reverseLayout && visual != null) {
                visualNewestIndex(visual) ?: 0
            } else {
                val messageTarget = autoScrollTargetIndex(messages)
                if (timeline != null && messageTarget != null) {
                    timelineIndexForMessageIndex(timeline, messageTarget) ?: messageTarget
                } else {
                    messageTarget
                }
            }
            target?.let { listState.scrollToTimelineIndex(it) }
        }
        lastSeenCount = messages.size
        lastSeenLastId = messages.lastOrNull()?.id
    }
}

/** Подгрузка старой истории, когда пользователь подходит к верху timeline. */
@Composable
fun ChatOlderMessagesEffect(
    chatId: String,
    messages: List<Message>,
    listState: LazyListState,
    repository: MessengerRepository,
    loadingOlder: Boolean,
    refreshInFlight: Boolean,
    onLoadingOlderChange: (Boolean) -> Unit,
    onError: (String?) -> Unit,
) {
    LaunchedEffect(chatId, listState, messages.firstOrNull()?.id, loadingOlder, refreshInFlight) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.layoutInfo.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (firstVisible, totalItems) ->
                if (loadingOlder || refreshInFlight) return@collect
                if (!shouldPrefetchOlder(
                        reverseLayout = true,
                        firstVisibleIndex = firstVisible,
                        totalItems = totalItems,
                    )
                ) {
                    return@collect
                }
                if (!repository.hasMoreMessages(chatId)) return@collect
                val beforeId = messages.firstOrNull()?.id ?: return@collect
                onLoadingOlderChange(true)
                try {
                    runCatching {
                        repository.loadOlderMessages(chatId, beforeId)
                    }.onFailure { onError(it.message) }
                } finally {
                    onLoadingOlderChange(false)
                }
            }
    }
}

/** Отправка сообщения и обработка ошибки на экране чата. */
fun CoroutineScope.sendChatMessage(
    repository: MessengerRepository,
    chatId: String,
    body: String,
    relation: MessageRelationDraft? = null,
    expiresAtSec: Long? = null,
    onError: (String?) -> Unit,
    onComplete: (() -> Unit)? = null,
) {
    launch {
        try {
            runCatching {
                // Channels are open (server-visible): never use e2e group outbox.
                val chat = repository.getLocalChat(chatId)
                if (chat?.isChannel == true) {
                    repository.sendChannelPost(
                        chatId = chatId,
                        body = body,
                        threadRootId = relation?.threadRootId,
                    )
                } else {
                    repository.sendMessage(
                        chatId = chatId,
                        body = body,
                        relation = relation,
                        expiresAtSec = expiresAtSec,
                    )
                }
            }.onFailure { onError(it.message) }
        } finally {
            onComplete?.invoke()
        }
    }
}

/**
 * Обновление канала чата (pull-to-refresh / свайп).
 * Outbox → history/sync → DM relay queue (Signal-style: drain send + fetch mailbox).
 */
suspend fun MessengerRepository.refreshChatChannel(chat: Chat) {
    runChatChannelRefresh(
        chat = chat,
        operations = object : ChatChannelRefreshOperations {
            override suspend fun recoverSession(): Boolean = tryRecoverSession(forceRefresh = true)

            override suspend fun retryUndelivered(chatId: String): OutboxRecoveryResult =
                retryUndeliveredMessagesWithResult(chatId = chatId)

            override suspend fun processOutbox(maxJobs: Int): OutboxProcessingResult =
                this@refreshChatChannel.processOutbox(maxJobs = maxJobs)

            override suspend fun syncMessages(chatId: String) {
                this@refreshChatChannel.syncMessages(chatId)
            }

            override suspend fun processMessageQueue(chatId: String, force: Boolean) {
                this@refreshChatChannel.processMessageQueue(chatId, force = force)
            }

            override suspend fun recoverAuthFailure(err: Throwable): Boolean =
                this@refreshChatChannel.recoverAuthFailure(err)

            override fun isTransientFailure(err: Throwable): Boolean =
                isTransientChatSyncFailure(err)
        },
    )
}

internal interface ChatChannelRefreshOperations {
    suspend fun recoverSession(): Boolean
    suspend fun retryUndelivered(chatId: String): OutboxRecoveryResult
    suspend fun processOutbox(maxJobs: Int): OutboxProcessingResult
    suspend fun syncMessages(chatId: String)
    suspend fun processMessageQueue(chatId: String, force: Boolean)
    suspend fun recoverAuthFailure(err: Throwable): Boolean
    fun isTransientFailure(err: Throwable): Boolean
}

internal class ChatRefreshIncompleteException(message: String) : Exception(message)

internal const val CHAT_REFRESH_INCOMPLETE_MESSAGE = TransientNetworkErrors.CHAT_REFRESH_ERROR
internal const val CHAT_REFRESH_AUTH_MESSAGE = "Нужно войти заново, чтобы обновить чат"

internal suspend fun runChatChannelRefresh(
    chat: Chat,
    operations: ChatChannelRefreshOperations,
) {
    if (!operations.recoverSession()) {
        throw ChatRefreshIncompleteException(CHAT_REFRESH_AUTH_MESSAGE)
    }

    var outboxResult = operations.retryUndelivered(chat.id).outbox
    outboxResult += operations.processOutbox(maxJobs = 20)

    runRefreshStep(operations) {
        operations.syncMessages(chat.id)
    }

    if (chat.isDirectMessage) {
        runRefreshStep(operations) {
            operations.processMessageQueue(chat.id, force = true)
        }
        outboxResult += operations.processOutbox(maxJobs = 20)
    }

    if (outboxResult.hasFailures) {
        throw ChatRefreshIncompleteException(outboxResult.chatRefreshErrorMessage())
    }
}

private suspend fun runRefreshStep(
    operations: ChatChannelRefreshOperations,
    block: suspend () -> Unit,
) {
    runCatching { block() }
        .onFailure { err ->
            if (operations.recoverAuthFailure(err)) {
                throw ChatRefreshIncompleteException(CHAT_REFRESH_AUTH_MESSAGE)
            }
            if (operations.isTransientFailure(err)) {
                throw ChatRefreshIncompleteException(CHAT_REFRESH_INCOMPLETE_MESSAGE)
            }
            throw err
        }
}

private fun OutboxProcessingResult.chatRefreshErrorMessage(): String =
    when {
        authDeferred -> CHAT_REFRESH_AUTH_MESSAGE
        transientFailures > 0 -> CHAT_REFRESH_INCOMPLETE_MESSAGE
        else -> lastError ?: "Не удалось обновить чат"
    }

private suspend fun MessengerRepository.reportSyncFailure(
    err: Throwable,
    onError: (String?) -> Unit,
) {
    if (recoverAuthFailure(err) || isTransientChatSyncFailure(err)) {
        onError(null)
    } else {
        onError(err.message)
    }
}

private fun isTransientChatSyncFailure(err: Throwable): Boolean =
    TransientNetworkErrors.isTransient(err)

internal suspend fun LazyListState.scrollToTimelineIndex(index: Int) {
    if (shouldAnimateScrollToItem(isScrollInProgress)) {
        runCatching { animateScrollToItem(index) }
            .onFailure { runCatching { scrollToItem(index) } }
    } else {
        runCatching { scrollToItem(index) }
    }
}
