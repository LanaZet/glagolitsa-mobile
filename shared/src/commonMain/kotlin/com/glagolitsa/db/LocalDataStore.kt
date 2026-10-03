// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import com.glagolitsa.currentIsoTimestamp
import com.glagolitsa.jobs.OutboxJobRecord
import com.glagolitsa.media.LocalAttachmentRecord
import com.glagolitsa.model.Chat
import com.glagolitsa.model.MESSAGE_VISIBILITY_THREAD_ONLY
import com.glagolitsa.model.Message
import com.glagolitsa.model.MessageStatus
import com.glagolitsa.model.User
import com.glagolitsa.model.sortedForChat
import com.glagolitsa.ui.navigation.MainTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PersistedSession(
    val token: String,
    val user: User,
)

private const val KEY_NAV_BAR_ORDER = "nav_bar_order"
private const val KEY_HIDDEN_CHAT_PREFIX = "hidden_chat:"

/** Локальное хранилище: SQLDelight. UI читает через Flow; repository — source of truth для расшифрованных сообщений. */
class LocalDataStore(
    private val driverFactory: DatabaseDriverFactory,
) {
    private data class StoreHandle(
        val namespace: String?,
        val driver: SqlDriver,
        val database: GlagolitsaDatabase,
    )

    private val switchMutex = Mutex()
    private val activeStore = MutableStateFlow(openStore(namespace = null))
    private val database: GlagolitsaDatabase get() = activeStore.value.database
    private val queries: GlagolitsaQueries get() = activeStore.value.database.glagolitsaQueries

    private fun openStore(namespace: String?): StoreHandle {
        val driver = driverFactory.createDriver(namespace)
        return StoreHandle(namespace = namespace, driver = driver, database = GlagolitsaDatabase(driver))
    }

    suspend fun switchAccount(accountId: String?) = switchMutex.withLock {
        val namespace = accountId?.takeIf { it.isNotBlank() }
        val current = activeStore.value
        if (current.namespace == namespace) return@withLock
        val next = openStore(namespace)
        migrateLegacyDefaultStoreIfNeeded(source = current, target = next)
        activeStore.value = next
        current.driver.close()
    }

    /**
     * One-time legacy bridge:
     * when switching from historical default DB to the new account namespace,
     * copy local history/settings for the same user so chats don't disappear after restart.
     */
    private fun migrateLegacyDefaultStoreIfNeeded(source: StoreHandle, target: StoreHandle) {
        val targetAccountId = target.namespace ?: return
        if (source.namespace != null) return

        val sourceQueries = source.database.glagolitsaQueries
        val targetQueries = target.database.glagolitsaQueries

        val sourceSession = sourceQueries.selectSession().executeAsOneOrNull() ?: return
        if (sourceSession.user_id != targetAccountId) return
        if (targetHasAccountData(targetQueries)) return

        val sourceChats = sourceQueries.selectChats().executeAsList()
        val sourceMessages = sourceQueries.selectAllMessages().executeAsList()
        val sourceAttachments = sourceQueries.selectAllLocalAttachments().executeAsList()
        val sourceSettings = sourceQueries.selectSettingsByPrefix("%").executeAsList()

        target.database.transaction {
            targetQueries.upsertSession(
                token = sourceSession.token,
                user_id = sourceSession.user_id,
                username = sourceSession.username,
                display_name = sourceSession.display_name,
                status = sourceSession.status,
                bio = sourceSession.bio,
                avatar_url = sourceSession.avatar_url,
                presence = sourceSession.presence,
                nickname = sourceSession.nickname,
                position = sourceSession.position,
            )
            sourceChats.forEach { row ->
                targetQueries.insertChat(
                    id = row.id,
                    title = row.title,
                    type = row.type,
                    last_message = row.last_message,
                    last_message_at = row.last_message_at,
                    created_at = row.created_at,
                    description = row.description,
                    visibility = row.visibility,
                    slug = row.slug,
                    encryption = row.encryption,
                    creator_id = row.creator_id,
                    avatar_url = row.avatar_url,
                )
            }
            sourceMessages.forEach { row ->
                targetQueries.insertMessage(
                    id = row.id,
                    chat_id = row.chat_id,
                    sender_id = row.sender_id,
                    body = row.body,
                    pending_id = row.pending_id,
                    status = row.status,
                    created_at = row.created_at,
                    reply_to_message_id = row.reply_to_message_id,
                    reply_preview_sender_id = row.reply_preview_sender_id,
                    reply_preview_body = row.reply_preview_body,
                    thread_root_id = row.thread_root_id,
                    thread_parent_id = row.thread_parent_id,
                    visibility = row.visibility,
                    thread_reply_count = row.thread_reply_count,
                    last_thread_reply_at = row.last_thread_reply_at,
                    last_thread_reply_sender_id = row.last_thread_reply_sender_id,
                    expires_at = row.expires_at,
                    envelope_type = row.envelope_type,
                    ciphertext = row.ciphertext,
                    sender_device_id = row.sender_device_id,
                )
            }
            sourceAttachments.forEach { row ->
                targetQueries.upsertLocalAttachment(
                    cache_id = row.cache_id,
                    attachment_id = row.attachment_id,
                    message_id = row.message_id,
                    chat_id = row.chat_id,
                    owner_account_id = row.owner_account_id,
                    direction = row.direction,
                    kind = row.kind,
                    mime_type = row.mime_type,
                    file_name = row.file_name,
                    width = row.width,
                    height = row.height,
                    duration_ms = row.duration_ms,
                    waveform = row.waveform,
                    encrypted_path = row.encrypted_path,
                    thumbnail_path = row.thumbnail_path,
                    thumbnail_key = row.thumbnail_key,
                    thumbnail_size = row.thumbnail_size,
                    encrypted_size = row.encrypted_size,
                    plaintext_size = row.plaintext_size,
                    state = row.state,
                    last_accessed_at = row.last_accessed_at,
                    created_at = row.created_at,
                    expires_at = row.expires_at,
                    error = row.error,
                )
            }
            sourceSettings.forEach { row ->
                targetQueries.upsertSetting(
                    setting_key = row.setting_key,
                    setting_value = row.setting_value,
                )
            }
        }
    }

    private fun targetHasAccountData(queries: GlagolitsaQueries): Boolean =
        queries.selectSession().executeAsOneOrNull() != null ||
            queries.selectChats().executeAsList().isNotEmpty() ||
            queries.countMessages().executeAsOne() > 0L

    fun close() {
        activeStore.value.driver.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeChats(): Flow<List<Chat>> =
        activeStore.flatMapLatest { store ->
            store.database.glagolitsaQueries.selectChats()
                .asFlow()
                .mapToList(Dispatchers.Default)
                .map { rows ->
                    val queries = store.database.glagolitsaQueries
                    rows
                        .filterNot { isChatHidden(queries, it.id) }
                        .map { resolveChatListPreview(it.toChat()) }
                }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMessages(chatId: String): Flow<List<Message>> =
        activeStore.flatMapLatest { store ->
            store.database.glagolitsaQueries.selectMessagesForChat(chatId)
                .asFlow()
                .mapToList(Dispatchers.Default)
                .map { rows -> rows.map { it.toMessage() }.sortedForChat() }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMainMessages(chatId: String): Flow<List<Message>> =
        activeStore.flatMapLatest { store ->
            store.database.glagolitsaQueries.selectMainMessagesForChat(chatId)
                .asFlow()
                .mapToList(Dispatchers.Default)
                .map { rows -> rows.map { it.toMessage() }.sortedForChat() }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeThreadMessages(chatId: String, threadRootId: String): Flow<List<Message>> =
        activeStore.flatMapLatest { store ->
            store.database.glagolitsaQueries.selectThreadMessagesForChat(chatId, threadRootId)
                .asFlow()
                .mapToList(Dispatchers.Default)
                .map { rows -> rows.map { it.toMessage() }.sortedForChat() }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeOutboxErrors(chatId: String): Flow<Map<String, String>> =
        activeStore.flatMapLatest { store ->
            store.database.glagolitsaQueries.selectOutboxJobsForChat(chatId)
                .asFlow()
                .mapToList(Dispatchers.Default)
                .map { rows ->
                    rows.mapNotNull { row ->
                        row.last_error
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { error -> row.id to error }
                    }.toMap()
                }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeThreadBranchMessages(chatId: String): Flow<List<Message>> =
        activeStore.flatMapLatest { store ->
            store.database.glagolitsaQueries.selectThreadBranchMessagesForChat(
                chat_id = chatId,
                mapper = ::messageFromRow,
            )
                .asFlow()
                .mapToList(Dispatchers.Default)
                .map { rows -> rows.sortedForChat() }
        }

    suspend fun loadCachedUser(): User? = withContext(Dispatchers.Default) {
        queries.selectSession().executeAsOneOrNull()?.toCachedUser()
    }

    /** @deprecated Токены хранятся в [com.glagolitsa.session.SecureSessionStore]; только миграция. */
    suspend fun loadLegacySession(): PersistedSession? = withContext(Dispatchers.Default) {
        runCatching {
            val row = queries.selectSession().executeAsOneOrNull() ?: return@runCatching null
            if (row.token.isBlank()) return@runCatching null
            PersistedSession(token = row.token, user = row.toCachedUser())
        }.getOrNull()
    }

    suspend fun saveCachedUser(user: User) = withContext(Dispatchers.Default) {
        queries.upsertSession(
            token = "",
            user_id = user.id,
            username = user.username,
            display_name = user.display_name,
            status = user.status,
            bio = user.bio,
            avatar_url = user.avatar_url,
            presence = user.presence,
            nickname = user.nickname,
            position = user.position,
        )
    }

    private fun com.glagolitsa.db.App_session.toCachedUser(): User = User(
        id = user_id,
        username = username,
        display_name = display_name,
        status = status,
        bio = bio,
        avatar_url = avatar_url,
        presence = presence,
        nickname = nickname,
        position = position,
    )

    suspend fun replaceChats(chats: List<Chat>) = withContext(Dispatchers.Default) {
        database.transaction {
            val remoteIds = chats.map { it.id }.toSet()
            queries.selectChats().executeAsList().forEach { row ->
                val local = row.toChat()
                if ((local.isGroup || local.isChannel) && local.id !in remoteIds) {
                    deleteChatLocally(local.id)
                }
            }
            chats.forEach { chat -> insertChatRecord(chat) }
        }
    }

    suspend fun replaceMessages(chatId: String, messages: List<Message>) = withContext(Dispatchers.Default) {
        database.transaction {
            val remoteIds = messages.map { it.id }.toSet()
            val remotePendingIds = messages.mapNotNull { it.pending_id?.takeIf(String::isNotBlank) }.toSet()
            val unsyncedLocalMessages = queries.selectMessagesForChat(chatId)
                .executeAsList()
                .map { it.toMessage() }
                .filter { message ->
                    val localPendingId = message.pending_id?.takeIf(String::isNotBlank)
                    val isUnsent = message.status == MessageStatus.SENDING || message.status == MessageStatus.FAILED
                    isUnsent &&
                        message.id !in remoteIds &&
                        (localPendingId == null || localPendingId !in remotePendingIds)
                }
            queries.deleteMessagesForChat(chatId)
            messages.forEach { message -> insertMessageRecord(message, "sent") }
            unsyncedLocalMessages.forEach { message ->
                insertMessageRecord(message, message.status ?: MessageStatus.SENDING)
            }
            updateChatPreviewFromLatestMainMessage(chatId)
        }
    }

    suspend fun deleteChatFromList(chatId: String) = withContext(Dispatchers.Default) {
        database.transaction {
            deleteChatLocally(chatId)
        }
    }

    suspend fun prependMessages(messages: List<Message>) = withContext(Dispatchers.Default) {
        database.transaction {
            messages.forEach { message -> insertMessageRecord(message, "sent") }
            messages.map { it.chat_id }.distinct().forEach { chatId ->
                updateChatPreviewFromLatestMainMessage(chatId)
            }
        }
    }

    suspend fun saveChat(chat: Chat, revealHidden: Boolean = false) = withContext(Dispatchers.Default) {
        insertChatRecord(chat, revealHidden = revealHidden)
    }

    suspend fun findChat(chatId: String): Chat? = withContext(Dispatchers.Default) {
        queries.selectChatById(chatId).executeAsOneOrNull()?.toChat()
    }

    /** Snapshot of all chats (account history export / cloud restore). */
    suspend fun listAllChats(): List<Chat> = withContext(Dispatchers.Default) {
        queries.selectChats().executeAsList().map { it.toChat() }
    }

    /** Snapshot of all messages (account history export / cloud restore). */
    suspend fun listAllMessages(): List<Message> = withContext(Dispatchers.Default) {
        queries.selectAllMessages().executeAsList().map { it.toMessage() }
    }

    suspend fun countMessages(): Long = withContext(Dispatchers.Default) {
        queries.countMessages().executeAsOne()
    }

    /**
     * Merge remote history without wiping local rows (Telegram-style rehydrate).
     * Never deletes; INSERT OR REPLACE only fills gaps / updates previews.
     */
    suspend fun mergeAccountHistory(chats: List<Chat>, messages: List<Message>) =
        withContext(Dispatchers.Default) {
            database.transaction {
                chats.forEach { insertChatRecord(it) }
                messages.forEach { insertMessageRecord(it, it.status ?: "sent") }
                messages.map { it.chat_id }.distinct().forEach { chatId ->
                    updateChatPreviewFromLatestMainMessage(chatId)
                }
            }
        }

    suspend fun updateChatPreview(chatId: String, lastMessage: String?, lastMessageAt: String?) =
        withContext(Dispatchers.Default) {
            queries.updateChatPreview(
                last_message = lastMessage,
                last_message_at = lastMessageAt,
                id = chatId,
            )
        }

    suspend fun saveMessage(message: Message, status: String = "sent") = withContext(Dispatchers.Default) {
        database.transaction {
            insertMessageRecord(message, status)
            refreshThreadSummaryIfNeeded(message)
            if (message.visibility != MESSAGE_VISIBILITY_THREAD_ONLY) {
                updateChatPreviewFromLatestMainMessage(message.chat_id)
            }
        }
    }

    suspend fun findMessageById(messageId: String): Message? = withContext(Dispatchers.Default) {
        queries.selectMessageById(messageId).executeAsOneOrNull()?.toMessage()
    }

    suspend fun findMessageByPendingId(pendingId: String): Message? = withContext(Dispatchers.Default) {
        queries.selectMessageByPendingId(pendingId).executeAsOneOrNull()?.toMessage()
    }

    /**
     * Update message status with monotonic delivery transitions
     * ([com.glagolitsa.model.MessageStatus.applyTransition]): never downgrade
     * READ→SENT, SENT→SENDING, etc.
     */
    suspend fun updateMessageStatus(messageId: String, status: String) = withContext(Dispatchers.Default) {
        val row = queries.selectMessageById(messageId).executeAsOneOrNull() ?: return@withContext
        val next = com.glagolitsa.model.MessageStatus.applyTransition(row.status, status)
        if (next != row.status) {
            queries.updateMessageStatus(status = next, id = messageId)
        }
    }

    suspend fun updateMessageStatusByPendingId(
        chatId: String,
        senderId: String,
        pendingId: String,
        status: String,
    ) = withContext(Dispatchers.Default) {
        val rows = queries
            .selectMessagesByPendingIdForSender(
                chat_id = chatId,
                sender_id = senderId,
                pending_id = pendingId,
            )
            .executeAsList()
        database.transaction {
            for (row in rows) {
                val next = com.glagolitsa.model.MessageStatus.applyTransition(row.status, status)
                if (next != row.status) {
                    queries.updateMessageStatus(status = next, id = row.id)
                }
            }
        }
    }

    suspend fun enqueueOutboxJob(job: OutboxJobRecord) = withContext(Dispatchers.Default) {
        queries.insertOutboxJob(
            id = job.id,
            job_type = job.jobType,
            chat_id = job.chatId,
            payload_json = job.payloadJson,
            status = job.status,
            attempts = job.attempts.toLong(),
            next_attempt_at = job.nextAttemptAt,
            created_at = job.createdAt,
            last_error = job.lastError,
        )
    }

    suspend fun listDueOutboxJobs(nowIso: String, limit: Int): List<OutboxJobRecord> = withContext(Dispatchers.Default) {
        queries.selectDueOutboxJobs(nowIso, limit.toLong()).executeAsList().map { it.toOutboxJob() }
    }

    suspend fun updateOutboxJob(
        jobId: String,
        status: String,
        attempts: Int,
        nextAttemptAt: String?,
        lastError: String?,
    ) = withContext(Dispatchers.Default) {
        queries.updateOutboxJob(
            status = status,
            attempts = attempts.toLong(),
            next_attempt_at = nextAttemptAt,
            last_error = lastError,
            id = jobId,
        )
    }

    suspend fun resetRecoverableOutboxBackoff() = withContext(Dispatchers.Default) {
        queries.resetRecoverableOutboxBackoff()
    }

    suspend fun deleteOutboxJob(jobId: String) = withContext(Dispatchers.Default) {
        queries.deleteOutboxJob(jobId)
    }

    suspend fun hasPendingOutboxJobs(): Boolean = withContext(Dispatchers.Default) {
        queries.countPendingOutboxJobs().executeAsOne() > 0
    }

    suspend fun findOutboxJob(jobId: String): OutboxJobRecord? = withContext(Dispatchers.Default) {
        queries.selectOutboxJobById(jobId).executeAsOneOrNull()?.toOutboxJob()
    }

    suspend fun deleteMessageById(messageId: String) = withContext(Dispatchers.Default) {
        queries.deleteMessageById(messageId)
    }

    suspend fun loadNavBarOrder(): List<MainTab> = withContext(Dispatchers.Default) {
        val raw = queries.selectSetting(setting_key = KEY_NAV_BAR_ORDER).executeAsOneOrNull()
        MainTab.parseOrder(raw)
    }

    suspend fun saveNavBarOrder(order: List<MainTab>) = withContext(Dispatchers.Default) {
        queries.upsertSetting(
            setting_key = KEY_NAV_BAR_ORDER,
            setting_value = MainTab.encodeOrder(order),
        )
    }

    suspend fun loadSetting(key: String, defaultValue: String = ""): String = withContext(Dispatchers.Default) {
        queries.selectSetting(setting_key = key).executeAsOneOrNull() ?: defaultValue
    }

    suspend fun loadSettingsByPrefix(prefix: String): Map<String, String> = withContext(Dispatchers.Default) {
        queries.selectSettingsByPrefix("$prefix%")
            .executeAsList()
            .associate { it.setting_key to it.setting_value }
    }

    suspend fun saveSetting(key: String, value: String) = withContext(Dispatchers.Default) {
        queries.upsertSetting(setting_key = key, setting_value = value)
    }

    /**
     * **Dangerous:** wipes chats + messages + outbox + settings for the active account DB.
     *
     * Product rule: user history must NEVER be erased without explicit user permission.
     * Callers must pass [userConfirmedErase] = true; automatic paths (logout, reauth,
     * multi-device login, device rotate) must not call this.
     */
    suspend fun clear(userConfirmedErase: Boolean) = withContext(Dispatchers.Default) {
        require(userConfirmedErase) {
            "LocalDataStore.clear requires userConfirmedErase=true — history must not be wiped automatically"
        }
        database.transaction {
            queries.clearOutboxJobs()
            queries.clearMessages()
            queries.clearChats()
            queries.clearLocalAttachments()
            queries.clearSession()
            queries.clearSettings()
        }
    }

    suspend fun purgeExpiredMessages(nowIso: String = currentIsoTimestamp()) = withContext(Dispatchers.Default) {
        queries.deleteExpiredMessages(nowIso)
    }

    suspend fun upsertLocalAttachment(record: LocalAttachmentRecord) = withContext(Dispatchers.Default) {
        queries.upsertLocalAttachment(
            cache_id = record.cacheId,
            attachment_id = record.attachmentId,
            message_id = record.messageId,
            chat_id = record.chatId,
            owner_account_id = record.ownerAccountId,
            direction = record.direction,
            kind = record.kind,
            mime_type = record.mimeType,
            file_name = record.fileName,
            width = record.width?.toLong(),
            height = record.height?.toLong(),
            duration_ms = record.durationMs,
            waveform = record.waveform,
            encrypted_path = record.encryptedPath,
            thumbnail_path = record.thumbnailPath,
            thumbnail_key = record.thumbnailKey,
            thumbnail_size = record.thumbnailSize,
            encrypted_size = record.encryptedSize,
            plaintext_size = record.plaintextSize,
            state = record.state,
            last_accessed_at = record.lastAccessedAt,
            created_at = record.createdAt,
            expires_at = record.expiresAt,
            error = record.error,
        )
    }

    suspend fun findLocalAttachment(cacheId: String): LocalAttachmentRecord? = withContext(Dispatchers.Default) {
        queries.selectLocalAttachment(cacheId).executeAsOneOrNull()?.toLocalAttachmentRecord()
    }

    suspend fun findLocalAttachmentByAttachmentId(attachmentId: String): LocalAttachmentRecord? =
        withContext(Dispatchers.Default) {
            queries.selectLocalAttachmentByAttachmentId(attachmentId).executeAsOneOrNull()?.toLocalAttachmentRecord()
        }

    suspend fun listLocalAttachmentsForChat(chatId: String): List<LocalAttachmentRecord> =
        withContext(Dispatchers.Default) {
            queries.selectLocalAttachmentsForChat(chatId).executeAsList().map { it.toLocalAttachmentRecord() }
        }

    suspend fun listAllLocalAttachments(): List<LocalAttachmentRecord> = withContext(Dispatchers.Default) {
        queries.selectAllLocalAttachments().executeAsList().map { it.toLocalAttachmentRecord() }
    }

    suspend fun updateLocalAttachmentState(
        cacheId: String,
        state: String,
        error: String?,
        accessedAt: String,
    ) = withContext(Dispatchers.Default) {
        queries.updateLocalAttachmentState(state = state, error = error, last_accessed_at = accessedAt, cache_id = cacheId)
    }

    suspend fun updateLocalAttachmentRemoteId(
        cacheId: String,
        attachmentId: String,
        state: String,
        accessedAt: String,
    ) = withContext(Dispatchers.Default) {
        queries.updateLocalAttachmentRemoteId(
            attachment_id = attachmentId,
            state = state,
            last_accessed_at = accessedAt,
            cache_id = cacheId,
        )
    }

    suspend fun updateLocalAttachmentMessageId(
        cacheId: String,
        messageId: String,
        accessedAt: String,
    ) = withContext(Dispatchers.Default) {
        queries.updateLocalAttachmentMessageId(
            message_id = messageId,
            last_accessed_at = accessedAt,
            cache_id = cacheId,
        )
    }

    suspend fun relinkLocalAttachmentMessageId(
        fromMessageId: String,
        toMessageId: String,
        accessedAt: String,
    ) = withContext(Dispatchers.Default) {
        queries.relinkLocalAttachmentMessageId(
            message_id = toMessageId,
            last_accessed_at = accessedAt,
            message_id_ = fromMessageId,
        )
    }

    suspend fun repairLocalAttachmentMessageLinksForChat(
        chatId: String,
        accessedAt: String,
    ) = withContext(Dispatchers.Default) {
        queries.repairLocalAttachmentMessageLinksForChat(
            chat_id = chatId,
            last_accessed_at = accessedAt,
            chat_id_ = chatId,
            chat_id__ = chatId,
        )
    }

    suspend fun updateLocalAttachmentThumbnail(
        cacheId: String,
        thumbnailPath: String,
        thumbnailKey: String,
        thumbnailSize: Long,
        accessedAt: String,
    ) = withContext(Dispatchers.Default) {
        queries.updateLocalAttachmentThumbnail(
            thumbnail_path = thumbnailPath,
            thumbnail_key = thumbnailKey,
            thumbnail_size = thumbnailSize,
            last_accessed_at = accessedAt,
            cache_id = cacheId,
        )
    }

    suspend fun touchLocalAttachment(cacheId: String, accessedAt: String) = withContext(Dispatchers.Default) {
        queries.touchLocalAttachment(last_accessed_at = accessedAt, cache_id = cacheId)
    }

    suspend fun deleteLocalAttachmentRecord(cacheId: String) = withContext(Dispatchers.Default) {
        queries.deleteLocalAttachment(cacheId)
    }

    suspend fun deleteLocalAttachmentsForChat(chatId: String) = withContext(Dispatchers.Default) {
        queries.deleteLocalAttachmentsForChat(chatId)
    }

    suspend fun expiredLocalAttachments(nowIso: String): List<LocalAttachmentRecord> =
        withContext(Dispatchers.Default) {
            queries.selectExpiredLocalAttachments(nowIso).executeAsList().map { it.toLocalAttachmentRecord() }
        }

    suspend fun localAttachmentTrimCandidates(limit: Int): List<LocalAttachmentRecord> =
        withContext(Dispatchers.Default) {
            queries.selectLocalAttachmentTrimCandidates(limit.toLong()).executeAsList().map { it.toLocalAttachmentRecord() }
        }

    suspend fun sumLocalAttachmentBytes(): Long = withContext(Dispatchers.Default) {
        queries.sumLocalAttachmentBytes().executeAsOne()
    }

    private fun insertChatRecord(chat: Chat, revealHidden: Boolean = false) {
        if (revealHidden) {
            revealChat(chat.id)
        } else if (isChatHidden(queries, chat.id)) {
            return
        }
        val existing = queries.selectChatById(chat.id).executeAsOneOrNull()
        val serverPreview = chat.last_message.previewTextOrNull()
        val latestMessage = if (serverPreview == null || chat.last_message_at.isNullOrBlank()) {
            latestMainMessageForChat(chat.id)
        } else {
            null
        }
        val existingPreview = existing?.last_message.previewTextOrNull()
        val latestPreview = latestMessage?.body.previewTextOrNull()
        val fallbackPreview = chat.last_message.nonBlankTextOrNull()
            ?: existing?.last_message.nonBlankTextOrNull()
            ?: latestMessage?.body.nonBlankTextOrNull()
        val resolvedPreview = serverPreview ?: latestPreview ?: existingPreview ?: fallbackPreview
        val resolvedPreviewAt = when {
            serverPreview != null -> chat.last_message_at?.takeIf { it.isNotBlank() }
                ?: existing?.last_message_at?.takeIf { it.isNotBlank() }
                ?: latestMessage?.created_at?.takeIf { it.isNotBlank() }
            latestPreview != null -> latestMessage?.created_at?.takeIf { it.isNotBlank() }
                ?: existing?.last_message_at?.takeIf { it.isNotBlank() }
                ?: chat.last_message_at?.takeIf { it.isNotBlank() }
            existingPreview != null -> existing?.last_message_at?.takeIf { it.isNotBlank() }
                ?: latestMessage?.created_at?.takeIf { it.isNotBlank() }
                ?: chat.last_message_at?.takeIf { it.isNotBlank() }
            else -> chat.last_message_at?.takeIf { it.isNotBlank() }
                ?: existing?.last_message_at?.takeIf { it.isNotBlank() }
                ?: latestMessage?.created_at?.takeIf { it.isNotBlank() }
        }
        // Prefer explicit type; never let a blank server field wipe a known channel.
        val existingType = existing?.type?.trim().orEmpty()
        val incomingType = chat.type.trim()
        val resolvedType = when {
            incomingType.isNotEmpty() -> incomingType
            existingType.isNotEmpty() -> existingType
            else -> chat.type
        }
        queries.insertChat(
            id = chat.id,
            title = chat.title,
            type = resolvedType,
            last_message = resolvedPreview,
            last_message_at = resolvedPreviewAt,
            created_at = chat.created_at,
            description = chat.description?.takeIf { it.isNotBlank() } ?: existing?.description,
            visibility = chat.visibility?.takeIf { it.isNotBlank() } ?: existing?.visibility,
            slug = chat.slug?.takeIf { it.isNotBlank() } ?: existing?.slug,
            encryption = chat.encryption?.takeIf { it.isNotBlank() } ?: existing?.encryption,
            // Keep local creator across sync until the server exposes owner in listChats.
            creator_id = chat.creator_id?.takeIf { it.isNotBlank() } ?: existing?.creator_id,
            avatar_url = chat.avatar_url?.takeIf { it.isNotBlank() } ?: existing?.avatar_url,
        )
    }

    private fun resolveChatListPreview(chat: Chat): Chat {
        if (chat.last_message.previewTextOrNull() != null) return chat
        val latestMessage = latestMainMessageForChat(chat.id)
        val latestPreview = latestMessage?.body.previewTextOrNull() ?: return chat
        return chat.copy(
            last_message = latestPreview,
            last_message_at = latestMessage?.created_at?.takeIf { it.isNotBlank() } ?: chat.last_message_at,
        )
    }

    private fun latestMainMessageForChat(chatId: String): Message? =
        queries.selectMainMessagesForChat(chatId)
            .executeAsList()
            .map { it.toMessage() }
            .sortedForChat()
            .lastOrNull()

    private fun updateChatPreviewFromLatestMainMessage(chatId: String) {
        val latestMessage = latestMainMessageForChat(chatId)
        queries.updateChatPreview(
            last_message = latestMessage?.body?.takeIf { it.isNotBlank() },
            last_message_at = latestMessage?.created_at?.takeIf { it.isNotBlank() },
            id = chatId,
        )
    }

    private fun String?.previewTextOrNull(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() && !it.isEncryptedPreviewPlaceholder() }

    private fun String?.nonBlankTextOrNull(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }

    private fun String.isEncryptedPreviewPlaceholder(): Boolean {
        val normalized = trim()
            .removePrefix("🔒")
            .trim()
            .lowercase()
        return normalized == "сообщение" ||
            normalized == "зашифрованное сообщение" ||
            normalized == "encrypted message"
    }

    private fun hiddenChatKey(chatId: String): String = KEY_HIDDEN_CHAT_PREFIX + chatId

    private fun isChatHidden(queries: GlagolitsaQueries, chatId: String): Boolean =
        queries.selectSetting(hiddenChatKey(chatId)).executeAsOneOrNull() == "1"

    private fun hideChat(chatId: String) {
        queries.upsertSetting(
            setting_key = hiddenChatKey(chatId),
            setting_value = "1",
        )
    }

    private fun deleteChatLocally(chatId: String) {
        hideChat(chatId)
        queries.deleteMessagesForChat(chatId)
        queries.deleteLocalAttachmentsForChat(chatId)
        queries.deleteChatById(chatId)
    }

    private fun revealChat(chatId: String) {
        queries.upsertSetting(
            setting_key = hiddenChatKey(chatId),
            setting_value = "0",
        )
    }

    private fun insertMessageRecord(message: Message, status: String) {
        message.pending_id
            ?.takeIf { it.isNotBlank() }
            ?.let { pendingId ->
                queries.deleteMessagesByPendingIdExcept(
                    pending_id = pendingId,
                    chat_id = message.chat_id,
                    sender_id = message.sender_id,
                    id = message.id,
                )
            }
        queries.insertMessage(
            id = message.id,
            chat_id = message.chat_id,
            sender_id = message.sender_id,
            body = message.body,
            pending_id = message.pending_id,
            status = message.status ?: status,
            created_at = message.created_at,
            reply_to_message_id = message.reply_to_message_id,
            reply_preview_sender_id = message.reply_preview_sender_id,
            reply_preview_body = message.reply_preview_body,
            thread_root_id = message.thread_root_id,
            thread_parent_id = message.thread_parent_id,
            visibility = message.visibility ?: "main",
            thread_reply_count = message.thread_reply_count.toLong(),
            last_thread_reply_at = message.last_thread_reply_at,
            last_thread_reply_sender_id = message.last_thread_reply_sender_id,
            expires_at = message.expires_at,
            envelope_type = message.envelope_type?.toLong(),
            ciphertext = message.ciphertext,
            sender_device_id = message.sender_device_id,
        )
    }

    private fun refreshThreadSummaryIfNeeded(message: Message) {
        val rootId = message.thread_root_id?.takeIf { rootId ->
            message.visibility == MESSAGE_VISIBILITY_THREAD_ONLY &&
                rootId.isNotBlank() &&
                rootId != message.id
        } ?: return
        queries.refreshThreadSummaryFromLocalReplies(rootId)
    }
}

private fun Chats.toChat(): Chat = Chat(
    id = id,
    title = title,
    type = type,
    last_message = last_message,
    last_message_at = last_message_at,
    created_at = created_at,
    description = description,
    visibility = visibility,
    slug = slug,
    encryption = encryption,
    creator_id = creator_id,
    avatar_url = avatar_url,
)

private fun Outbox_jobs.toOutboxJob(): OutboxJobRecord = OutboxJobRecord(
    id = id,
    jobType = job_type,
    chatId = chat_id,
    payloadJson = payload_json,
    status = status,
    attempts = attempts.toInt(),
    nextAttemptAt = next_attempt_at,
    createdAt = created_at,
    lastError = last_error,
)

private fun Local_attachments.toLocalAttachmentRecord(): LocalAttachmentRecord = LocalAttachmentRecord(
    cacheId = cache_id,
    attachmentId = attachment_id,
    messageId = message_id,
    chatId = chat_id,
    ownerAccountId = owner_account_id,
    direction = direction,
    kind = kind,
    mimeType = mime_type,
    fileName = file_name,
    width = width?.toInt(),
    height = height?.toInt(),
    durationMs = duration_ms,
    waveform = waveform,
    encryptedPath = encrypted_path,
    thumbnailPath = thumbnail_path,
    thumbnailKey = thumbnail_key,
    thumbnailSize = thumbnail_size,
    encryptedSize = encrypted_size,
    plaintextSize = plaintext_size,
    state = state,
    lastAccessedAt = last_accessed_at,
    createdAt = created_at,
    expiresAt = expires_at,
    error = error,
)

private fun SelectExpiredLocalAttachments.toLocalAttachmentRecord(): LocalAttachmentRecord = LocalAttachmentRecord(
    cacheId = cache_id,
    attachmentId = attachment_id,
    messageId = message_id,
    chatId = chat_id,
    ownerAccountId = owner_account_id,
    direction = direction,
    kind = kind,
    mimeType = mime_type,
    fileName = file_name,
    width = width?.toInt(),
    height = height?.toInt(),
    durationMs = duration_ms,
    waveform = waveform,
    encryptedPath = encrypted_path,
    thumbnailPath = thumbnail_path,
    thumbnailKey = thumbnail_key,
    thumbnailSize = thumbnail_size,
    encryptedSize = encrypted_size,
    plaintextSize = plaintext_size,
    state = state,
    lastAccessedAt = last_accessed_at,
    createdAt = created_at,
    expiresAt = expires_at,
    error = error,
)

private fun Messages.toMessage(): Message = Message(
    id = id,
    chat_id = chat_id,
    sender_id = sender_id,
    body = body,
    pending_id = pending_id,
    status = status,
    created_at = created_at,
    reply_to_message_id = reply_to_message_id,
    reply_preview_sender_id = reply_preview_sender_id,
    reply_preview_body = reply_preview_body,
    thread_root_id = thread_root_id,
    thread_parent_id = thread_parent_id,
    visibility = visibility,
    thread_reply_count = thread_reply_count.toInt(),
    last_thread_reply_at = last_thread_reply_at,
    last_thread_reply_sender_id = last_thread_reply_sender_id,
    expires_at = expires_at,
    envelope_type = envelope_type?.toInt(),
    ciphertext = ciphertext,
    sender_device_id = sender_device_id,
)

private fun messageFromRow(
    id: String,
    chat_id: String,
    sender_id: String,
    body: String,
    pending_id: String?,
    status: String,
    created_at: String?,
    reply_to_message_id: String?,
    reply_preview_sender_id: String?,
    reply_preview_body: String?,
    thread_root_id: String,
    thread_parent_id: String?,
    visibility: String,
    thread_reply_count: Long,
    last_thread_reply_at: String?,
    last_thread_reply_sender_id: String?,
    expires_at: String?,
    envelope_type: Long?,
    ciphertext: String?,
    sender_device_id: String?,
): Message = Message(
    id = id,
    chat_id = chat_id,
    sender_id = sender_id,
    body = body,
    pending_id = pending_id,
    status = status,
    created_at = created_at,
    reply_to_message_id = reply_to_message_id,
    reply_preview_sender_id = reply_preview_sender_id,
    reply_preview_body = reply_preview_body,
    thread_root_id = thread_root_id,
    thread_parent_id = thread_parent_id,
    visibility = visibility,
    thread_reply_count = thread_reply_count.toInt(),
    last_thread_reply_at = last_thread_reply_at,
    last_thread_reply_sender_id = last_thread_reply_sender_id,
    expires_at = expires_at,
    envelope_type = envelope_type?.toInt(),
    ciphertext = ciphertext,
    sender_device_id = sender_device_id,
)
