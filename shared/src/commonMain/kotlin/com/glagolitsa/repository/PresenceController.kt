// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.api.ApiClient
import com.glagolitsa.crypto.CryptoEngine
import com.glagolitsa.model.ChatPresenceView
import com.glagolitsa.model.HeartbeatRequest
import com.glagolitsa.model.PresencePrivacySettings
import com.glagolitsa.model.PresenceStatus
import com.glagolitsa.model.RecordingPresenceRequest
import com.glagolitsa.model.TypingChatRequest
import com.glagolitsa.model.UpdatePresencePrivacyRequest
import com.glagolitsa.model.UserPresenceView
import com.glagolitsa.session.SessionStore
import com.glagolitsa.session.SessionTokenProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PresenceController(
    private val api: ApiClient,
    private val crypto: () -> CryptoEngine,
    private val auth: SessionTokenProvider,
) {
    private val _partnerPresence = MutableStateFlow<Map<String, UserPresenceView>>(emptyMap())
    val partnerPresence: StateFlow<Map<String, UserPresenceView>> = _partnerPresence.asStateFlow()

    private val _chatPresence = MutableStateFlow<ChatPresenceView?>(null)
    val chatPresence: StateFlow<ChatPresenceView?> = _chatPresence.asStateFlow()

    suspend fun heartbeat() {
        val user = SessionStore.user.value ?: return
        runCatching {
            auth.withAuth { token ->
                val deviceId = crypto().ensureDeviceIdentity(user.id).deviceId
                api.presenceHeartbeat(token, deviceId, HeartbeatRequest(device_id = deviceId))
            }
        }
    }

    suspend fun fetchUsers(userIds: List<String>): List<UserPresenceView> {
        if (userIds.isEmpty()) return emptyList()
        return runCatching {
            auth.withAuth { token ->
                api.listPresenceUsers(token, userIds)
            }
        }.fold(
            onSuccess = { response ->
                _partnerPresence.value = _partnerPresence.value + response.associateBy { it.user_id }
                response
            },
            onFailure = { emptyList() },
        )
    }

    suspend fun fetchChatPresence(chatId: String): ChatPresenceView? =
        runCatching {
            auth.withAuth { token ->
                api.getChatPresence(token, chatId)
            }
        }.fold(
            onSuccess = { view ->
                _chatPresence.value = view
                view
            },
            onFailure = { null },
        )

    suspend fun typingStart(chatId: String) {
        runCatching {
            auth.withAuth { token ->
                api.presenceTypingStart(token, TypingChatRequest(chat_id = chatId))
            }
        }
    }

    suspend fun typingStop(chatId: String) {
        runCatching {
            auth.withAuth { token ->
                api.presenceTypingStop(token, TypingChatRequest(chat_id = chatId))
            }
        }
    }

    suspend fun recordingStart(chatId: String, voice: Boolean = true) {
        runCatching {
            auth.withAuth { token ->
                api.presenceRecordingStart(
                    token,
                    RecordingPresenceRequest(
                        chat_id = chatId,
                        kind = if (voice) "voice" else "video",
                    ),
                )
            }
        }
    }

    suspend fun recordingStop(chatId: String) {
        runCatching {
            auth.withAuth { token ->
                api.presenceRecordingStop(token, TypingChatRequest(chat_id = chatId))
            }
        }
    }

    suspend fun getPrivacy(): PresencePrivacySettings =
        runCatching {
            auth.withAuth { token ->
                api.getPresencePrivacy(token)
            }
        }.getOrDefault(PresencePrivacySettings())

    suspend fun updatePrivacy(request: UpdatePresencePrivacyRequest): PresencePrivacySettings =
        auth.withAuth { token ->
            api.updatePresencePrivacy(token, request)
        }

    fun applyPartnerOnline(userId: String) {
        val current = _partnerPresence.value[userId]
        _partnerPresence.value = _partnerPresence.value + (userId to presenceAfterOnlineEvent(current, userId))
    }

    fun applyPartnerOffline(userId: String) {
        val current = _partnerPresence.value[userId]
        _partnerPresence.value = _partnerPresence.value + (userId to presenceAfterOfflineEvent(current, userId))
    }

    /** Realtime call.started — network channel only (not profile about). */
    fun applyPartnerInCall(userId: String, callId: String? = null) {
        val current = _partnerPresence.value[userId]
        _partnerPresence.value = _partnerPresence.value + (userId to presenceAfterInCallEvent(current, userId, callId))
    }

    /** Realtime call.ended — clear stuck in_call after cancel/hangup. */
    fun applyPartnerCallEnded(userId: String) {
        val current = _partnerPresence.value[userId]
        val nextStatus = when {
            current == null -> PresenceStatus.ONLINE
            current.status == PresenceStatus.IN_CALL || current.in_call -> PresenceStatus.ONLINE
            else -> current.status
        }
        _partnerPresence.value = _partnerPresence.value + (
            userId to (current?.copy(
                status = nextStatus,
                last_seen_bucket = if (nextStatus == PresenceStatus.ONLINE) null else current.last_seen_bucket,
                in_call = false,
                call_id = null,
            )
                ?: UserPresenceView(user_id = userId, status = nextStatus))
            )
    }
}

internal fun presenceAfterOnlineEvent(current: UserPresenceView?, userId: String): UserPresenceView =
    current?.copy(
        status = PresenceStatus.ONLINE,
        last_seen_bucket = null,
        in_call = false,
        call_id = null,
    ) ?: UserPresenceView(user_id = userId, status = PresenceStatus.ONLINE)

internal fun presenceAfterOfflineEvent(current: UserPresenceView?, userId: String): UserPresenceView =
    current?.copy(
        status = PresenceStatus.OFFLINE,
        last_seen_bucket = null,
        in_call = false,
        call_id = null,
    ) ?: UserPresenceView(user_id = userId, status = PresenceStatus.OFFLINE)

internal fun presenceAfterInCallEvent(
    current: UserPresenceView?,
    userId: String,
    callId: String?,
): UserPresenceView =
    current?.copy(
        status = PresenceStatus.IN_CALL,
        last_seen_bucket = null,
        in_call = true,
        call_id = callId ?: current.call_id,
    ) ?: UserPresenceView(
        user_id = userId,
        status = PresenceStatus.IN_CALL,
        in_call = true,
        call_id = callId,
    )
