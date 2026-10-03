// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.api

import com.glagolitsa.auth.RegistrationDeferredException
import com.glagolitsa.model.AckMessageQueueRequest
import com.glagolitsa.model.CallActionResponse
import com.glagolitsa.model.CallKeyOffer
import com.glagolitsa.model.ActiveCallResponse
import com.glagolitsa.model.CallSession
import com.glagolitsa.model.CallTokenResponse
import com.glagolitsa.model.CreateCallRequest
import com.glagolitsa.model.ICEServersResponse
import com.glagolitsa.model.SubmitCallKeysRequest
import com.glagolitsa.model.AckMessageQueueResponse
import com.glagolitsa.model.AttachmentInfo
import com.glagolitsa.model.CreateAttachmentResponse
import com.glagolitsa.model.ApiError
import com.glagolitsa.model.AuthResponse
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ConfirmDeviceRequest
import com.glagolitsa.model.ChannelResponse
import com.glagolitsa.model.CreateChannelRequest
import com.glagolitsa.model.CreateChatRequest
import com.glagolitsa.model.CreateDMRequest
import com.glagolitsa.model.UpdateChatRequest
import com.glagolitsa.model.BanMemberRequest
import com.glagolitsa.model.CreateGroupInviteRequest
import com.glagolitsa.model.GroupInviteDto
import com.glagolitsa.model.GroupInvitePreviewDto
import com.glagolitsa.model.GroupResponse
import com.glagolitsa.model.MembershipChangeDto
import com.glagolitsa.model.MuteMemberRequest
import com.glagolitsa.model.UpdateGroupSettingsRequest
import com.glagolitsa.model.UpdateMemberRoleRequest
import com.glagolitsa.model.SlugAvailabilityResponse
import com.glagolitsa.model.DeviceKeyBundle
import com.glagolitsa.model.ClientUpdatePolicy
import com.glagolitsa.model.HealthResponse
import com.glagolitsa.model.HelloResponse
import com.glagolitsa.model.LoginRequest
import com.glagolitsa.model.Message
import com.glagolitsa.model.MessageQueueResponse
import com.glagolitsa.model.NotificationPreferences
import com.glagolitsa.model.RegisterPushTokenRequest
import com.glagolitsa.model.RegisterPushTokenResponse
import com.glagolitsa.model.UpdateNotificationPreferencesRequest
import com.glagolitsa.model.MessagesPageResponse
import com.glagolitsa.model.CreateMediaSlotRequest
import com.glagolitsa.model.CreateMediaSlotResponse
import com.glagolitsa.model.MediaContentMode
import com.glagolitsa.model.MediaFileInfo
import com.glagolitsa.model.MarkCallConnectedRequest
import com.glagolitsa.model.ReactionUpdateResponse
import com.glagolitsa.model.SetReactionRequest
import com.glagolitsa.model.RegisterDeviceRequest
import com.glagolitsa.model.KeyChangeEvent
import com.glagolitsa.model.ChatPresenceView
import com.glagolitsa.model.HeartbeatRequest
import com.glagolitsa.model.PrekeyCountResponse
import com.glagolitsa.model.PresencePrivacySettings
import com.glagolitsa.model.RecordingPresenceRequest
import com.glagolitsa.model.TypingChatRequest
import com.glagolitsa.model.UpdatePresencePrivacyRequest
import com.glagolitsa.model.UserPresenceView
import com.glagolitsa.model.RegisterDeviceResponse
import com.glagolitsa.model.ReplenishPrekeysRequest
import com.glagolitsa.model.RotateSignedPreKeyRequest
import com.glagolitsa.model.SafetyNumberMaterial
import com.glagolitsa.model.RefreshRequest
import com.glagolitsa.model.PowChallengeResponse
import com.glagolitsa.model.RegisterRequest
import com.glagolitsa.model.RelayMessageRequest
import com.glagolitsa.model.RelayMessageResponse
import com.glagolitsa.model.RotateMailboxResponse
import com.glagolitsa.model.SendMessageRequest
import com.glagolitsa.model.UpdateProfileRequest
import com.glagolitsa.model.User
import com.glagolitsa.model.UserDevice
import com.glagolitsa.model.UsersByIdsRequest
import com.glagolitsa.util.sha256Hex
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.WebSockets
import com.glagolitsa.platform.AppRuntimeInfo
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random

private const val ATTACHMENT_UPLOAD_CHUNK_SIZE = 1 * 1024 * 1024

class ApiClient(
    private val baseUrl: String = defaultBaseUrl(),
    httpClient: HttpClient? = null,
    webSocketHttpClient: HttpClient? = null,
) {
    val serverBaseUrl: String = baseUrl.trim().trimEnd('/')

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * REST client — short timeouts, dedicated engine/pool (HTTP/1.1 on Android/desktop).
     * Do not open WebSockets on this instance.
     */
    val http = httpClient ?: HttpClient(createHttpEngine()) {
        install(HttpTimeout) {
            // REST only. Long-lived WebSocket uses [wsHttp].
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 15_000
        }
        install(ContentNegotiation) {
            json(json)
        }
    }

    /**
     * Realtime client — separate engine/pool so WS ping stalls cannot block REST
     * (create/accept/refresh hung for full 15s under shared OkHttp+H2).
     */
    val wsHttp = webSocketHttpClient ?: HttpClient(createWebSocketHttpEngine()) {
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = WebSocketTransport.CONNECT_TIMEOUT_MS
        }
        install(WebSockets) {
            pingIntervalMillis = WebSocketTransport.PING_INTERVAL_MS
        }
    }

    suspend fun health(): HealthResponse =
        http.get("$baseUrl/api/health").body()

    suspend fun hello(): HelloResponse =
        http.get("$baseUrl/api/hello").body()

    /** Public Mattermost-style update policy (min/latest + installable store/download URLs). */
    suspend fun getClientUpdatePolicy(): ClientUpdatePolicy {
        val response = http.get("$baseUrl/api/client/update-policy")
        if (!response.status.isSuccess()) {
            val errorText = runCatching { response.body<ApiError>().error }.getOrNull()
            throw ApiException(response.status, errorText)
        }
        return response.body()
    }

    suspend fun fetchPowChallenge(): PowChallengeResponse =
        http.get("$baseUrl/api/auth/pow").body()

    suspend fun register(request: RegisterRequest): AuthResponse {
        val response = http.post("$baseUrl/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(TextContent(json.encodeToString(RegisterRequest.serializer(), request), ContentType.Application.Json))
        }
        when (response.status) {
            HttpStatusCode.Created -> {
                return json.decodeFromString(AuthResponse.serializer(), response.body())
            }
            HttpStatusCode.Accepted -> {
                val message = runCatching {
                    json.decodeFromString<com.glagolitsa.model.ApiMessage>(response.body()).message
                }.getOrNull()
                    ?: "Если аккаунт не существует, регистрация будет завершена после привязки устройства"
                throw RegistrationDeferredException(message)
            }
            else -> {
                val errorText = runCatching { response.body<ApiError>().error }.getOrNull()
                throw ApiException(response.status, errorText)
            }
        }
    }

    suspend fun login(request: LoginRequest): AuthResponse =
        postJson("$baseUrl/api/auth/login", request, LoginRequest.serializer(), AuthResponse.serializer())

    suspend fun refresh(request: RefreshRequest): AuthResponse =
        postJson("$baseUrl/api/auth/refresh", request, RefreshRequest.serializer(), AuthResponse.serializer())

    suspend fun setupRecovery(token: String, request: com.glagolitsa.model.RecoverySetupRequest): com.glagolitsa.model.RecoveryStatusResponse =
        authorizedPost(
            "$baseUrl/api/recovery/setup",
            token,
            request,
            com.glagolitsa.model.RecoverySetupRequest.serializer(),
            com.glagolitsa.model.RecoveryStatusResponse.serializer(),
        )

    suspend fun recoveryStatus(token: String): com.glagolitsa.model.RecoveryStatusResponse =
        authorizedGet(
            "$baseUrl/api/recovery/status",
            token,
            com.glagolitsa.model.RecoveryStatusResponse.serializer(),
        )

    suspend fun verifyRecovery(request: com.glagolitsa.model.RecoveryVerifyRequest): com.glagolitsa.model.RecoveryTicketResponse =
        postJson(
            "$baseUrl/api/recovery/verify",
            request,
            com.glagolitsa.model.RecoveryVerifyRequest.serializer(),
            com.glagolitsa.model.RecoveryTicketResponse.serializer(),
        )

    suspend fun completeRecovery(request: com.glagolitsa.model.RecoveryCompleteRequest): com.glagolitsa.model.RecoveryCompleteResponse =
        postJson(
            "$baseUrl/api/recovery/complete",
            request,
            com.glagolitsa.model.RecoveryCompleteRequest.serializer(),
            com.glagolitsa.model.RecoveryCompleteResponse.serializer(),
        )

    suspend fun startTrustedRecovery(request: com.glagolitsa.model.TrustedRecoveryStartRequest): com.glagolitsa.model.TrustedRecoveryStartResponse =
        postJson(
            "$baseUrl/api/recovery/trusted/start",
            request,
            com.glagolitsa.model.TrustedRecoveryStartRequest.serializer(),
            com.glagolitsa.model.TrustedRecoveryStartResponse.serializer(),
        )

    suspend fun pendingTrustedRecovery(token: String): com.glagolitsa.model.TrustedRecoveryPendingResponse =
        authorizedGet(
            "$baseUrl/api/recovery/trusted/pending",
            token,
            com.glagolitsa.model.TrustedRecoveryPendingResponse.serializer(),
        )

    suspend fun approveTrustedRecovery(
        token: String,
        request: com.glagolitsa.model.TrustedRecoveryApproveRequest,
    ) {
        authorizedPostEmpty(
            "$baseUrl/api/recovery/trusted/approve",
            token,
            request,
            com.glagolitsa.model.TrustedRecoveryApproveRequest.serializer(),
        )
    }

    suspend fun pollTrustedRecovery(request: com.glagolitsa.model.TrustedRecoveryPollRequest): com.glagolitsa.model.RecoveryTicketResponse =
        postJson(
            "$baseUrl/api/recovery/trusted/poll",
            request,
            com.glagolitsa.model.TrustedRecoveryPollRequest.serializer(),
            com.glagolitsa.model.RecoveryTicketResponse.serializer(),
        )

    suspend fun webAuthnRegisterBegin(token: String): com.glagolitsa.model.WebAuthnBeginResponse =
        authorizedPost(
            "$baseUrl/api/auth/webauthn/register/begin",
            token,
            com.glagolitsa.model.WebAuthnBeginRequest(),
            com.glagolitsa.model.WebAuthnBeginRequest.serializer(),
            com.glagolitsa.model.WebAuthnBeginResponse.serializer(),
        )

    suspend fun webAuthnRegisterFinish(
        token: String,
        request: com.glagolitsa.model.WebAuthnFinishRequest,
    ): com.glagolitsa.model.RecoveryStatusResponse =
        authorizedPost(
            "$baseUrl/api/auth/webauthn/register/finish",
            token,
            request,
            com.glagolitsa.model.WebAuthnFinishRequest.serializer(),
            com.glagolitsa.model.RecoveryStatusResponse.serializer(),
        )

    suspend fun webAuthnLoginBegin(request: com.glagolitsa.model.WebAuthnBeginRequest): com.glagolitsa.model.WebAuthnBeginResponse =
        postJson(
            "$baseUrl/api/auth/webauthn/login/begin",
            request,
            com.glagolitsa.model.WebAuthnBeginRequest.serializer(),
            com.glagolitsa.model.WebAuthnBeginResponse.serializer(),
        )

    suspend fun webAuthnLoginFinish(request: com.glagolitsa.model.WebAuthnFinishRequest): AuthResponse =
        postJson(
            "$baseUrl/api/auth/webauthn/login/finish",
            request,
            com.glagolitsa.model.WebAuthnFinishRequest.serializer(),
            AuthResponse.serializer(),
        )

    suspend fun webAuthnRecoveryBegin(request: com.glagolitsa.model.WebAuthnBeginRequest): com.glagolitsa.model.WebAuthnBeginResponse =
        postJson(
            "$baseUrl/api/recovery/passkey/begin",
            request,
            com.glagolitsa.model.WebAuthnBeginRequest.serializer(),
            com.glagolitsa.model.WebAuthnBeginResponse.serializer(),
        )

    suspend fun webAuthnRecoveryFinish(request: com.glagolitsa.model.WebAuthnFinishRequest): com.glagolitsa.model.RecoveryTicketResponse =
        postJson(
            "$baseUrl/api/recovery/passkey/finish",
            request,
            com.glagolitsa.model.WebAuthnFinishRequest.serializer(),
            com.glagolitsa.model.RecoveryTicketResponse.serializer(),
        )

    suspend fun logout(token: String) {
        val response = http.post("$baseUrl/api/auth/logout") {
            header("Authorization", "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            val errorText = runCatching { response.body<ApiError>().error }.getOrNull()
            throw ApiException(response.status, errorText)
        }
    }

    suspend fun listChats(token: String): List<Chat> =
        authorizedGet("$baseUrl/api/chats", token, ListSerializer(Chat.serializer()))

    suspend fun saveSyncSnapshot(
        token: String,
        request: com.glagolitsa.model.SaveSyncSnapshotRequest,
    ): com.glagolitsa.model.SyncSnapshotResponse =
        authorizedPost(
            "$baseUrl/api/sync/snapshot",
            token,
            request,
            com.glagolitsa.model.SaveSyncSnapshotRequest.serializer(),
            com.glagolitsa.model.SyncSnapshotResponse.serializer(),
        )

    suspend fun getSyncSnapshot(token: String): com.glagolitsa.model.SyncSnapshotResponse =
        authorizedGet(
            "$baseUrl/api/sync/snapshot",
            token,
            com.glagolitsa.model.SyncSnapshotResponse.serializer(),
        )

    suspend fun sync(token: String, since: String? = null, limit: Int = 100): com.glagolitsa.model.SyncResponse {
        val query = buildString {
            append("$baseUrl/api/sync?limit=$limit")
            if (!since.isNullOrBlank()) append("&since=$since")
        }
        return authorizedGet(query, token, com.glagolitsa.model.SyncResponse.serializer())
    }

    suspend fun createChat(token: String, request: CreateChatRequest): Chat =
        authorizedPost("$baseUrl/api/chats", token, request, CreateChatRequest.serializer(), Chat.serializer())

    suspend fun updateChat(token: String, chatId: String, request: UpdateChatRequest): Chat =
        authorizedPatch(
            "$baseUrl/api/chats/${encodeQueryParam(chatId)}",
            token,
            request,
            UpdateChatRequest.serializer(),
            Chat.serializer(),
        )

    suspend fun createChannel(token: String, request: CreateChannelRequest): ChannelResponse =
        authorizedPost(
            "$baseUrl/api/channels",
            token,
            request,
            CreateChannelRequest.serializer(),
            ChannelResponse.serializer(),
        )

    suspend fun checkChannelSlugAvailable(token: String, slug: String): SlugAvailabilityResponse =
        authorizedGet(
            "$baseUrl/api/channels/slug-available?slug=${encodeQueryParam(slug)}",
            token,
            SlugAvailabilityResponse.serializer(),
        )

    /** Public channel discover — any authenticated user (Telegram/Element directory style). */
    suspend fun searchPublicChannels(token: String, query: String): List<ChannelResponse> =
        authorizedGet(
            "$baseUrl/api/channels/search?q=${encodeQueryParam(query)}",
            token,
            ListSerializer(ChannelResponse.serializer()),
        )

    suspend fun joinChannelBySlug(token: String, slug: String): ChannelResponse {
        val response = http.post("$baseUrl/api/channels/slug/${encodeQueryParam(slug)}/join") {
            header("Authorization", "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
        return json.decodeFromString(ChannelResponse.serializer(), response.body())
    }

    /** Permanent wipe of a group or channel (`DELETE /api/groups/{id}`). */
    suspend fun deleteGroup(token: String, groupId: String) {
        authorizedDelete("$baseUrl/api/groups/${encodeQueryParam(groupId)}", token)
    }

    /** Leave channel/group membership (`POST /api/groups/{id}/leave`). */
    suspend fun leaveGroup(token: String, groupId: String) {
        val response = http.post("$baseUrl/api/groups/${encodeQueryParam(groupId)}/leave") {
            header("Authorization", "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
    }

    /** Group/channel governance (members + roles + settings). */
    suspend fun getGroup(token: String, groupId: String): GroupResponse =
        authorizedGet(
            "$baseUrl/api/groups/${encodeQueryParam(groupId)}",
            token,
            GroupResponse.serializer(),
        )

    suspend fun updateGroupSettings(
        token: String,
        groupId: String,
        request: UpdateGroupSettingsRequest,
    ): GroupResponse = authorizedPatch(
        "$baseUrl/api/groups/${encodeQueryParam(groupId)}/settings",
        token,
        request,
        UpdateGroupSettingsRequest.serializer(),
        GroupResponse.serializer(),
    )

    suspend fun createGroupInvite(
        token: String,
        groupId: String,
        request: CreateGroupInviteRequest,
    ): GroupInviteDto = authorizedPost(
        "$baseUrl/api/groups/${encodeQueryParam(groupId)}/invites",
        token,
        request,
        CreateGroupInviteRequest.serializer(),
        GroupInviteDto.serializer(),
    )

    suspend fun listGroupInvites(token: String, groupId: String): List<GroupInviteDto> =
        authorizedGet(
            "$baseUrl/api/groups/${encodeQueryParam(groupId)}/invites",
            token,
            ListSerializer(GroupInviteDto.serializer()),
        )

    suspend fun revokeGroupInvite(token: String, groupId: String, inviteId: String) {
        authorizedDelete(
            "$baseUrl/api/groups/${encodeQueryParam(groupId)}/invites/${encodeQueryParam(inviteId)}",
            token,
        )
    }

    suspend fun previewGroupInvite(token: String, inviteToken: String): GroupInvitePreviewDto =
        authorizedGet(
            "$baseUrl/api/group-invites/${encodeQueryParam(inviteToken)}",
            token,
            GroupInvitePreviewDto.serializer(),
        )

    suspend fun joinGroupInvite(token: String, inviteToken: String): MembershipChangeDto {
        val response = http.post("$baseUrl/api/group-invites/${encodeQueryParam(inviteToken)}/join") {
            header("Authorization", "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
        return json.decodeFromString(MembershipChangeDto.serializer(), response.body())
    }

    suspend fun updateGroupMemberRole(
        token: String,
        groupId: String,
        userId: String,
        role: String,
    ) {
        authorizedPatchEmpty(
            "$baseUrl/api/groups/${encodeQueryParam(groupId)}/members/${encodeQueryParam(userId)}",
            token,
            UpdateMemberRoleRequest(role = role),
            UpdateMemberRoleRequest.serializer(),
        )
    }

    suspend fun muteGroupMember(
        token: String,
        groupId: String,
        userId: String,
        durationMinutes: Int,
    ) {
        authorizedPostEmpty(
            "$baseUrl/api/groups/${encodeQueryParam(groupId)}/members/${encodeQueryParam(userId)}/mute",
            token,
            MuteMemberRequest(duration_minutes = durationMinutes),
            MuteMemberRequest.serializer(),
        )
    }

    suspend fun unmuteGroupMember(token: String, groupId: String, userId: String) {
        authorizedDelete(
            "$baseUrl/api/groups/${encodeQueryParam(groupId)}/members/${encodeQueryParam(userId)}/mute",
            token,
        )
    }

    suspend fun removeGroupMember(token: String, groupId: String, userId: String): MembershipChangeDto =
        authorizedDeleteJson(
            "$baseUrl/api/groups/${encodeQueryParam(groupId)}/members/${encodeQueryParam(userId)}",
            token,
            MembershipChangeDto.serializer(),
        )

    suspend fun banGroupMember(
        token: String,
        groupId: String,
        userId: String,
        reason: String? = null,
    ): MembershipChangeDto =
        authorizedPost(
            "$baseUrl/api/groups/${encodeQueryParam(groupId)}/bans/${encodeQueryParam(userId)}",
            token,
            BanMemberRequest(reason = reason),
            BanMemberRequest.serializer(),
            MembershipChangeDto.serializer(),
        )

    suspend fun unbanGroupMember(token: String, groupId: String, userId: String) {
        authorizedDelete(
            "$baseUrl/api/groups/${encodeQueryParam(groupId)}/bans/${encodeQueryParam(userId)}",
            token,
        )
    }

    suspend fun searchUsers(token: String, query: String): List<User> =
        authorizedGet(
            "$baseUrl/api/users/search?q=${encodeQueryParam(query)}",
            token,
            ListSerializer(User.serializer()),
        )

    /** Быстрый prefix-поиск для autocomplete (limit 8 на сервере). */
    suspend fun autocompleteUsers(token: String, query: String): List<User> =
        authorizedGet(
            "$baseUrl/api/users/autocomplete?q=${encodeQueryParam(query)}",
            token,
            ListSerializer(User.serializer()),
        )

    /** Exact match по username — один hop для полностью введенного логина. */
    suspend fun lookupUser(token: String, username: String): User? {
        val users = authorizedGet(
            "$baseUrl/api/users/lookup?username=${encodeQueryParam(username)}",
            token,
            ListSerializer(User.serializer()),
        )
        return users.firstOrNull()
    }

    /**
     * Mattermost-style batch profile cards for chat-list avatars.
     * POST /api/users/ids { "ids": ["…"] } → users with avatar_url when set.
     */
    suspend fun getUsersByIds(token: String, ids: List<String>): List<User> {
        if (ids.isEmpty()) return emptyList()
        return authorizedPost(
            "$baseUrl/api/users/ids",
            token,
            UsersByIdsRequest(ids = ids.distinct().take(50)),
            UsersByIdsRequest.serializer(),
            ListSerializer(User.serializer()),
        )
    }

    suspend fun getMyProfile(token: String): User =
        authorizedGet("$baseUrl/api/users/me", token, User.serializer())

    suspend fun updateMyProfile(token: String, request: UpdateProfileRequest): User =
        authorizedPatch("$baseUrl/api/users/me", token, request, UpdateProfileRequest.serializer(), User.serializer())

    suspend fun createDM(token: String, request: CreateDMRequest): Chat =
        authorizedPost("$baseUrl/api/chats/dm", token, request, CreateDMRequest.serializer(), Chat.serializer())

    suspend fun listMessages(
        token: String,
        chatId: String,
        before: String? = null,
        limit: Int = 50,
    ): MessagesPageResponse {
        val query = buildString {
            append("$baseUrl/api/chats/$chatId/messages?limit=$limit")
            if (!before.isNullOrBlank()) {
                append("&before=$before")
            }
        }
        return authorizedGet(query, token, MessagesPageResponse.serializer())
    }

    /** Channel root posts feed (excludes thread comments). */
    suspend fun listChannelPosts(
        token: String,
        chatId: String,
        before: String? = null,
        limit: Int = 50,
    ): MessagesPageResponse {
        val query = buildString {
            append("$baseUrl/api/chats/$chatId/posts?limit=$limit")
            if (!before.isNullOrBlank()) {
                append("&before=$before")
            }
        }
        return authorizedGet(query, token, MessagesPageResponse.serializer())
    }

    /** Channel gallery: root posts that have metadata.media. */
    suspend fun listChannelGallery(
        token: String,
        chatId: String,
        before: String? = null,
        limit: Int = 50,
    ): MessagesPageResponse {
        val query = buildString {
            append("$baseUrl/api/chats/$chatId/gallery?limit=$limit")
            if (!before.isNullOrBlank()) {
                append("&before=$before")
            }
        }
        return authorizedGet(query, token, MessagesPageResponse.serializer())
    }

    suspend fun sendMessage(token: String, chatId: String, request: SendMessageRequest): Message =
        authorizedPost(
            "$baseUrl/api/chats/$chatId/messages",
            token,
            request,
            SendMessageRequest.serializer(),
            Message.serializer(),
        )

    suspend fun setMessageReaction(token: String, messageId: String, emoji: String): ReactionUpdateResponse =
        authorizedPut(
            "$baseUrl/api/messages/$messageId/reactions",
            token,
            SetReactionRequest(emoji = emoji),
            SetReactionRequest.serializer(),
            ReactionUpdateResponse.serializer(),
        )

    suspend fun clearMessageReaction(token: String, messageId: String): ReactionUpdateResponse {
        val response = http.delete("$baseUrl/api/messages/$messageId/reactions") {
            header("Authorization", "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
        return json.decodeFromString(ReactionUpdateResponse.serializer(), response.body())
    }

    suspend fun deleteMessage(token: String, chatId: String, messageId: String) {
        val response = http.delete("$baseUrl/api/chats/$chatId/messages/$messageId") {
            header("Authorization", "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
    }

    suspend fun registerPushToken(
        token: String,
        deviceId: String,
        request: RegisterPushTokenRequest,
    ): RegisterPushTokenResponse =
        authorizedPost(
            "$baseUrl/api/notification/tokens",
            token,
            request,
            RegisterPushTokenRequest.serializer(),
            RegisterPushTokenResponse.serializer(),
            deviceId = deviceId,
        )

    suspend fun getNotificationPreferences(token: String): NotificationPreferences =
        authorizedGet(
            "$baseUrl/api/notification/preferences",
            token,
            NotificationPreferences.serializer(),
        )

    suspend fun updateNotificationPreferences(
        token: String,
        request: UpdateNotificationPreferencesRequest,
    ): NotificationPreferences =
        authorizedPatch(
            "$baseUrl/api/notification/preferences",
            token,
            request,
            UpdateNotificationPreferencesRequest.serializer(),
            NotificationPreferences.serializer(),
        )

    suspend fun registerDevice(
        token: String,
        deviceId: String,
        request: RegisterDeviceRequest,
    ): RegisterDeviceResponse =
        authorizedPost(
            "$baseUrl/api/devices",
            token,
            request,
            RegisterDeviceRequest.serializer(),
            RegisterDeviceResponse.serializer(),
            deviceId = deviceId,
        )

    suspend fun getDeviceBundle(token: String, deviceId: String): DeviceKeyBundle =
        authorizedGet(
            "$baseUrl/api/devices/$deviceId/bundle",
            token,
            DeviceKeyBundle.serializer(),
        )

    suspend fun listUserDevices(token: String, userId: String): List<UserDevice> =
        authorizedGet(
            "$baseUrl/api/users/$userId/devices",
            token,
            ListSerializer(UserDevice.serializer()),
        )

    suspend fun confirmDevice(token: String, deviceId: String, confirmingDeviceId: String) {
        authorizedPostEmpty(
            "$baseUrl/api/devices/$deviceId/confirm",
            token,
            ConfirmDeviceRequest(confirming_device_id = confirmingDeviceId),
            ConfirmDeviceRequest.serializer(),
            deviceId = deviceId,
        )
    }

    suspend fun revokeDevice(token: String, deviceId: String) {
        authorizedDelete(
            "$baseUrl/api/devices/$deviceId",
            token,
            deviceId = deviceId,
        )
    }

    suspend fun replenishPrekeys(
        token: String,
        deviceId: String,
        request: ReplenishPrekeysRequest,
    ): RegisterDeviceResponse =
        authorizedPost(
            "$baseUrl/api/devices/$deviceId/prekeys",
            token,
            request,
            ReplenishPrekeysRequest.serializer(),
            RegisterDeviceResponse.serializer(),
            deviceId = deviceId,
        )

    suspend fun countRemainingPrekeys(token: String, deviceId: String): PrekeyCountResponse =
        authorizedGet(
            "$baseUrl/api/devices/$deviceId/prekeys/count",
            token,
            PrekeyCountResponse.serializer(),
            deviceId = deviceId,
        )

    suspend fun rotateSignedPreKey(
        token: String,
        deviceId: String,
        request: RotateSignedPreKeyRequest,
    ) {
        authorizedPostEmpty(
            "$baseUrl/api/devices/$deviceId/signed-prekey/rotate",
            token,
            request,
            RotateSignedPreKeyRequest.serializer(),
            deviceId = deviceId,
        )
    }

    suspend fun listKeyChanges(token: String, userId: String, limit: Int = 50): List<KeyChangeEvent> =
        authorizedGet(
            "$baseUrl/api/users/$userId/key-changes?limit=$limit",
            token,
            ListSerializer(KeyChangeEvent.serializer()),
        )

    suspend fun getSafetyNumberMaterial(token: String, deviceId: String): SafetyNumberMaterial =
        authorizedGet(
            "$baseUrl/api/devices/$deviceId/safety-number",
            token,
            SafetyNumberMaterial.serializer(),
        )

    suspend fun relayMessage(token: String, deviceId: String, request: RelayMessageRequest): RelayMessageResponse =
        authorizedPost(
            "$baseUrl/api/messages/relay",
            token,
            request,
            RelayMessageRequest.serializer(),
            RelayMessageResponse.serializer(),
            deviceId = deviceId,
        )

    suspend fun fetchMessageQueue(token: String, deviceId: String, limit: Int = 50): MessageQueueResponse =
        authorizedGet(
            "$baseUrl/api/messages/queue?limit=$limit",
            token,
            MessageQueueResponse.serializer(),
            deviceId = deviceId,
        )

    suspend fun ackMessageQueue(
        token: String,
        deviceId: String,
        request: AckMessageQueueRequest,
    ): AckMessageQueueResponse =
        authorizedPost(
            "$baseUrl/api/messages/queue/ack",
            token,
            request,
            AckMessageQueueRequest.serializer(),
            AckMessageQueueResponse.serializer(),
            deviceId = deviceId,
        )

    suspend fun createAttachment(token: String): CreateAttachmentResponse {
        val response = http.post("$baseUrl/api/attachments") {
            header("Authorization", "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
        return json.decodeFromString(CreateAttachmentResponse.serializer(), response.body())
    }

    /**
     * Channel open media: create slot with content_mode=open.
     * Do not use for DM/group e2e attachments.
     */
    suspend fun createOpenMediaSlot(
        token: String,
        chatId: String,
        mimeType: String = "image/jpeg",
    ): CreateMediaSlotResponse =
        authorizedPost(
            "$baseUrl/api/media/upload-slots",
            token,
            CreateMediaSlotRequest(
                kind = "photo",
                chat_id = chatId,
                mime_type = mimeType,
                content_mode = MediaContentMode.OPEN,
            ),
            CreateMediaSlotRequest.serializer(),
            CreateMediaSlotResponse.serializer(),
        )

    /** DM/group E2EE media: encrypted upload slot, replacing legacy /api/attachments. */
    suspend fun createEncryptedMediaSlot(
        token: String,
        kind: String,
        mimeType: String = "application/octet-stream",
    ): CreateMediaSlotResponse =
        authorizedPost(
            "$baseUrl/api/media/upload-slots",
            token,
            CreateMediaSlotRequest(
                kind = kind,
                mime_type = mimeType,
                content_mode = MediaContentMode.ENCRYPTED,
            ),
            CreateMediaSlotRequest.serializer(),
            CreateMediaSlotResponse.serializer(),
        )

    /** Upload plaintext channel photo bytes into an open slot. */
    suspend fun uploadOpenMedia(
        token: String,
        fileId: String,
        bytes: ByteArray,
    ): MediaFileInfo {
        if (bytes.isEmpty()) {
            throw ApiException(HttpStatusCode.BadRequest, "open media body is required")
        }
        val response = http.put("$baseUrl/api/media/files/$fileId") {
            header("Authorization", "Bearer $token")
            header("X-Media-Content-Mode", MediaContentMode.OPEN)
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
        return json.decodeFromString(MediaFileInfo.serializer(), response.body())
    }

    /** Download open channel media (membership-gated). Do not use for e2e attachments. */
    suspend fun downloadOpenMedia(token: String, fileId: String): ByteArray {
        val response = http.get("$baseUrl/api/media/files/$fileId") {
            header("Authorization", "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
        return response.body()
    }

    private suspend fun uploadAttachmentSingleShot(token: String, attachmentId: String, encryptedBytes: ByteArray): AttachmentInfo {
        val response = http.put("$baseUrl/api/attachments/$attachmentId") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.OctetStream)
            setBody(encryptedBytes)
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
        return json.decodeFromString(AttachmentInfo.serializer(), response.body())
    }

    private suspend fun uploadEncryptedMediaSingleShot(
        token: String,
        fileId: String,
        encryptedBytes: ByteArray,
    ): MediaFileInfo {
        val response = http.put("$baseUrl/api/media/files/$fileId") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.OctetStream)
            setBody(encryptedBytes)
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
        return json.decodeFromString(MediaFileInfo.serializer(), response.body())
    }

    suspend fun uploadEncryptedMedia(
        token: String,
        fileId: String,
        encryptedBytes: ByteArray,
    ): MediaFileInfo {
        if (encryptedBytes.isEmpty()) {
            throw ApiException(HttpStatusCode.BadRequest, "encrypted media body is required")
        }
        return uploadEncryptedMedia(
            token = token,
            fileId = fileId,
            encryptedSize = encryptedBytes.size.toLong(),
            readChunk = { offset, maxBytes ->
                val start = offset.toInt()
                val end = minOf(start + maxBytes, encryptedBytes.size)
                encryptedBytes.copyOfRange(start, end)
            },
        )
    }

    suspend fun uploadEncryptedMedia(
        token: String,
        fileId: String,
        encryptedSize: Long,
        readChunk: suspend (offset: Long, maxBytes: Int) -> ByteArray,
    ): MediaFileInfo {
        if (encryptedSize <= 0L) {
            throw ApiException(HttpStatusCode.BadRequest, "encrypted media body is required")
        }
        val chunkSize = ATTACHMENT_UPLOAD_CHUNK_SIZE
        if (encryptedSize <= chunkSize) {
            val bytes = readChunk(0L, encryptedSize.toInt())
            if (bytes.size.toLong() != encryptedSize) {
                throw ApiException(HttpStatusCode.BadRequest, "media chunk reader returned incomplete body")
            }
            return uploadEncryptedMediaSingleShot(token, fileId, bytes)
        }
        val uploadId = "upl-${Random.nextLong().toString(16).replace("-", "")}"
        var offset = 0L
        var finalInfo: MediaFileInfo? = null
        while (offset < encryptedSize) {
            val requested = minOf(chunkSize.toLong(), encryptedSize - offset).toInt()
            val chunk = readChunk(offset, requested)
            if (chunk.isEmpty() || chunk.size > requested) {
                throw ApiException(HttpStatusCode.BadRequest, "media chunk reader returned invalid chunk")
            }
            val next = offset + chunk.size
            val isLast = next == encryptedSize
            val response = http.put("$baseUrl/api/media/files/$fileId") {
                header("Authorization", "Bearer $token")
                header("X-Upload-Id", uploadId)
                header("X-Upload-Offset", offset.toString())
                header("X-Chunk-SHA256", sha256Hex(chunk))
                header("X-Upload-Complete", isLast.toString())
                contentType(ContentType.Application.OctetStream)
                setBody(chunk)
            }
            when {
                response.status == HttpStatusCode.Accepted -> {
                    if (isLast) throw ApiException(response.status, "media upload did not finalize")
                    offset = next
                }
                response.status.isSuccess() -> {
                    finalInfo = json.decodeFromString(MediaFileInfo.serializer(), response.body())
                    offset = next
                }
                else -> throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
            }
        }
        return finalInfo ?: throw ApiException(HttpStatusCode.InternalServerError, "media upload failed")
    }

    suspend fun uploadAttachment(token: String, attachmentId: String, encryptedBytes: ByteArray): AttachmentInfo {
        if (encryptedBytes.isEmpty()) {
            throw ApiException(HttpStatusCode.BadRequest, "encrypted attachment body is required")
        }
        return uploadAttachment(
            token = token,
            attachmentId = attachmentId,
            encryptedSize = encryptedBytes.size.toLong(),
            readChunk = { offset, maxBytes ->
                val start = offset.toInt()
                val end = minOf(start + maxBytes, encryptedBytes.size)
                encryptedBytes.copyOfRange(start, end)
            },
        )
    }

    suspend fun uploadAttachment(
        token: String,
        attachmentId: String,
        encryptedSize: Long,
        readChunk: suspend (offset: Long, maxBytes: Int) -> ByteArray,
    ): AttachmentInfo {
        if (encryptedSize <= 0L) {
            throw ApiException(HttpStatusCode.BadRequest, "encrypted attachment body is required")
        }
        val chunkSize = ATTACHMENT_UPLOAD_CHUNK_SIZE
        if (encryptedSize <= chunkSize) {
            val bytes = readChunk(0L, encryptedSize.toInt())
            if (bytes.size.toLong() != encryptedSize) {
                throw ApiException(HttpStatusCode.BadRequest, "attachment chunk reader returned incomplete body")
            }
            return uploadAttachmentSingleShot(token, attachmentId, bytes)
        }
        val uploadId = "upl-${Random.nextLong().toString(16).replace("-", "")}"
        var offset = 0L
        var finalInfo: AttachmentInfo? = null
        while (offset < encryptedSize) {
            val requested = minOf(chunkSize.toLong(), encryptedSize - offset).toInt()
            val chunk = readChunk(offset, requested)
            if (chunk.isEmpty() || chunk.size > requested) {
                throw ApiException(HttpStatusCode.BadRequest, "attachment chunk reader returned invalid chunk")
            }
            val next = offset + chunk.size
            val isLast = next == encryptedSize
            val response = http.put("$baseUrl/api/attachments/$attachmentId") {
                header("Authorization", "Bearer $token")
                header("X-Upload-Id", uploadId)
                header("X-Upload-Offset", offset.toString())
                header("X-Chunk-SHA256", sha256Hex(chunk))
                header("X-Upload-Complete", isLast.toString())
                contentType(ContentType.Application.OctetStream)
                setBody(chunk)
            }
            when {
                response.status == HttpStatusCode.Accepted -> {
                    if (isLast) throw ApiException(response.status, "upload did not finalize")
                    offset = next
                }
                response.status.isSuccess() -> {
                    finalInfo = json.decodeFromString(AttachmentInfo.serializer(), response.body())
                    offset = next
                }
                else -> throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
            }
        }
        return finalInfo ?: throw ApiException(HttpStatusCode.InternalServerError, "attachment upload failed")
    }

    suspend fun downloadAttachment(token: String, attachmentId: String): ByteArray {
        val mediaResponse = http.get("$baseUrl/api/media/files/$attachmentId") {
            header("Authorization", "Bearer $token")
        }
        if (mediaResponse.status.isSuccess()) {
            return mediaResponse.body()
        }
        if (mediaResponse.status != HttpStatusCode.NotFound && mediaResponse.status != HttpStatusCode.MethodNotAllowed) {
            throw ApiException(mediaResponse.status, runCatching { mediaResponse.body<ApiError>().error }.getOrNull())
        }
        val response = http.get("$baseUrl/api/attachments/$attachmentId") {
            header("Authorization", "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
        return response.body()
    }

    suspend fun rotateMailbox(token: String, deviceId: String): RotateMailboxResponse {
        val response = http.post("$baseUrl/api/devices/$deviceId/mailbox/rotate") {
            header("Authorization", "Bearer $token")
            header("X-Device-Id", deviceId)
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
        return json.decodeFromString(RotateMailboxResponse.serializer(), response.body())
    }

    suspend fun createCall(
        token: String,
        deviceId: String,
        request: CreateCallRequest,
    ): CallActionResponse =
        authorizedPost(
            "$baseUrl/api/calls",
            token,
            request,
            CreateCallRequest.serializer(),
            CallActionResponse.serializer(),
            deviceId = deviceId,
        )

    suspend fun getCall(token: String, callId: String): CallSession =
        authorizedGet("$baseUrl/api/calls/$callId", token, CallSession.serializer())

    suspend fun getActiveCallForChat(token: String, chatId: String): CallSession? =
        authorizedGet(
            "$baseUrl/api/calls/active?chat_id=${encodeQueryParam(chatId)}",
            token,
            ActiveCallResponse.serializer(),
        ).call

    suspend fun joinCall(token: String, callId: String, deviceId: String): CallActionResponse =
        authorizedPostNoBody("$baseUrl/api/calls/$callId/join", token, deviceId)

    suspend fun listCallHistory(token: String, limit: Int = 50): List<CallSession> =
        authorizedGet(
            "$baseUrl/api/calls/history?limit=$limit",
            token,
            ListSerializer(CallSession.serializer()),
        )

    suspend fun acceptCall(token: String, callId: String, deviceId: String): CallActionResponse =
        authorizedPostNoBody("$baseUrl/api/calls/$callId/accept", token, deviceId)

    suspend fun rejectCall(token: String, callId: String): CallActionResponse =
        authorizedPostNoBody("$baseUrl/api/calls/$callId/reject", token)

    suspend fun endCall(token: String, callId: String, deviceId: String?): CallActionResponse =
        authorizedPostNoBody("$baseUrl/api/calls/$callId/end", token, deviceId)

    suspend fun markCallConnected(token: String, callId: String, deviceId: String): CallActionResponse =
        authorizedPost(
            "$baseUrl/api/calls/$callId/connected",
            token,
            MarkCallConnectedRequest(media_path_confirmed = true),
            MarkCallConnectedRequest.serializer(),
            CallActionResponse.serializer(),
            deviceId = deviceId,
        )

    suspend fun getCallToken(token: String, callId: String, deviceId: String): CallTokenResponse =
        authorizedGet(
            "$baseUrl/api/calls/$callId/token",
            token,
            CallTokenResponse.serializer(),
            deviceId = deviceId,
        )

    suspend fun submitCallKeys(
        token: String,
        callId: String,
        deviceId: String,
        request: SubmitCallKeysRequest,
    ) {
        authorizedPostEmpty(
            "$baseUrl/api/calls/$callId/keys",
            token,
            request,
            SubmitCallKeysRequest.serializer(),
            deviceId = deviceId,
        )
    }

    suspend fun listCallKeys(token: String, callId: String, deviceId: String): List<CallKeyOffer> =
        authorizedGet(
            "$baseUrl/api/calls/$callId/keys",
            token,
            ListSerializer(CallKeyOffer.serializer()),
            deviceId = deviceId,
        )

    suspend fun enableLowBandwidth(token: String, callId: String): CallActionResponse =
        authorizedPostNoBody("$baseUrl/api/calls/$callId/low-bandwidth", token)

    suspend fun getIceServers(token: String): ICEServersResponse =
        authorizedGet("$baseUrl/api/ice/servers", token, ICEServersResponse.serializer())

    suspend fun presenceHeartbeat(token: String, deviceId: String, request: HeartbeatRequest) {
        authorizedPostEmpty(
            "$baseUrl/api/presence/heartbeat",
            token,
            request,
            HeartbeatRequest.serializer(),
            deviceId = deviceId,
        )
    }

    suspend fun presenceTypingStart(token: String, request: TypingChatRequest) {
        authorizedPostEmpty(
            "$baseUrl/api/presence/typing/start",
            token,
            request,
            TypingChatRequest.serializer(),
        )
    }

    suspend fun presenceTypingStop(token: String, request: TypingChatRequest) {
        authorizedPostEmpty(
            "$baseUrl/api/presence/typing/stop",
            token,
            request,
            TypingChatRequest.serializer(),
        )
    }

    suspend fun presenceRecordingStart(token: String, request: RecordingPresenceRequest) {
        authorizedPostEmpty(
            "$baseUrl/api/presence/recording/start",
            token,
            request,
            RecordingPresenceRequest.serializer(),
        )
    }

    suspend fun presenceRecordingStop(token: String, request: TypingChatRequest) {
        authorizedPostEmpty(
            "$baseUrl/api/presence/recording/stop",
            token,
            request,
            TypingChatRequest.serializer(),
        )
    }

    suspend fun listPresenceUsers(token: String, userIds: List<String>): List<UserPresenceView> {
        val ids = userIds.joinToString(",")
        val response = authorizedGet(
            "$baseUrl/api/presence/users?ids=$ids",
            token,
            com.glagolitsa.model.UsersPresenceResponse.serializer(),
        )
        return response.users
    }

    suspend fun getChatPresence(token: String, chatId: String): ChatPresenceView =
        authorizedGet(
            "$baseUrl/api/presence/chat/$chatId",
            token,
            ChatPresenceView.serializer(),
        )

    suspend fun getPresencePrivacy(token: String): PresencePrivacySettings =
        authorizedGet("$baseUrl/api/presence/privacy", token, PresencePrivacySettings.serializer())

    suspend fun updatePresencePrivacy(
        token: String,
        request: UpdatePresencePrivacyRequest,
    ): PresencePrivacySettings =
        authorizedPost(
            "$baseUrl/api/presence/privacy",
            token,
            request,
            UpdatePresencePrivacyRequest.serializer(),
            PresencePrivacySettings.serializer(),
            // Public path can be slow; keep the optimistic profile switch from reverting too early.
            requestTimeoutMs = 30_000,
            socketTimeoutMs = 30_000,
        )

    fun webSocketUrl(token: String): String {
        val wsBase = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        return "$wsBase/api/ws?token=$token"
    }

    private suspend fun <Req, Res> postJson(
        url: String,
        body: Req,
        requestSerializer: KSerializer<Req>,
        responseSerializer: KSerializer<Res>,
    ): Res {
        val response = http.post(url) {
            contentType(ContentType.Application.Json)
            setBody(TextContent(json.encodeToString(requestSerializer, body), ContentType.Application.Json))
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
        return json.decodeFromString(responseSerializer, response.body())
    }

    private suspend fun <Res> authorizedGet(
        url: String,
        token: String,
        responseSerializer: KSerializer<Res>,
        deviceId: String? = null,
    ): Res {
        val response = http.get(url) {
            header("Authorization", "Bearer $token")
            applyClientVersionHeaders()
            deviceId?.let { header("X-Device-Id", it) }
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
        return json.decodeFromString(responseSerializer, response.body())
    }

    private suspend fun <Req, Res> authorizedPatch(
        url: String,
        token: String,
        body: Req,
        requestSerializer: KSerializer<Req>,
        responseSerializer: KSerializer<Res>,
    ): Res {
        val response = http.patch(url) {
            header("Authorization", "Bearer $token")
            applyClientVersionHeaders()
            contentType(ContentType.Application.Json)
            setBody(TextContent(json.encodeToString(requestSerializer, body), ContentType.Application.Json))
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
        return json.decodeFromString(responseSerializer, response.body())
    }

    private suspend fun <Req, Res> authorizedPut(
        url: String,
        token: String,
        body: Req,
        requestSerializer: KSerializer<Req>,
        responseSerializer: KSerializer<Res>,
    ): Res {
        val response = http.put(url) {
            header("Authorization", "Bearer $token")
            applyClientVersionHeaders()
            contentType(ContentType.Application.Json)
            setBody(TextContent(json.encodeToString(requestSerializer, body), ContentType.Application.Json))
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
        return json.decodeFromString(responseSerializer, response.body())
    }

    private suspend fun <Req, Res> authorizedPost(
        url: String,
        token: String,
        body: Req,
        requestSerializer: KSerializer<Req>,
        responseSerializer: KSerializer<Res>,
        deviceId: String? = null,
        requestTimeoutMs: Long? = null,
        socketTimeoutMs: Long? = null,
    ): Res {
        val response = http.post(url) {
            if (requestTimeoutMs != null || socketTimeoutMs != null) {
                timeout {
                    requestTimeoutMs?.let { requestTimeoutMillis = it }
                    socketTimeoutMs?.let { socketTimeoutMillis = it }
                }
            }
            header("Authorization", "Bearer $token")
            applyClientVersionHeaders()
            deviceId?.let { header("X-Device-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(TextContent(json.encodeToString(requestSerializer, body), ContentType.Application.Json))
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
        return json.decodeFromString(responseSerializer, response.body())
    }

    private suspend fun authorizedPostNoBody(
        url: String,
        token: String,
        deviceId: String? = null,
    ): CallActionResponse {
        val response = http.post(url) {
            header("Authorization", "Bearer $token")
            applyClientVersionHeaders()
            deviceId?.let { header("X-Device-Id", it) }
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
        return json.decodeFromString(CallActionResponse.serializer(), response.body())
    }

    private suspend fun <Req> authorizedPatchEmpty(
        url: String,
        token: String,
        body: Req,
        requestSerializer: KSerializer<Req>,
    ) {
        val response = http.patch(url) {
            header("Authorization", "Bearer $token")
            applyClientVersionHeaders()
            contentType(ContentType.Application.Json)
            setBody(TextContent(json.encodeToString(requestSerializer, body), ContentType.Application.Json))
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
    }

    private suspend fun <Req> authorizedPostEmpty(
        url: String,
        token: String,
        body: Req,
        requestSerializer: KSerializer<Req>,
        deviceId: String? = null,
    ) {
        val response = http.post(url) {
            header("Authorization", "Bearer $token")
            applyClientVersionHeaders()
            deviceId?.let { header("X-Device-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(TextContent(json.encodeToString(requestSerializer, body), ContentType.Application.Json))
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
    }

    private suspend fun <Res> authorizedDeleteJson(
        url: String,
        token: String,
        responseSerializer: KSerializer<Res>,
        deviceId: String? = null,
    ): Res {
        val response = http.delete(url) {
            header("Authorization", "Bearer $token")
            applyClientVersionHeaders()
            deviceId?.let { header("X-Device-Id", it) }
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
        return json.decodeFromString(responseSerializer, response.body())
    }

    private suspend fun authorizedDelete(
        url: String,
        token: String,
        deviceId: String? = null,
    ) {
        val response = http.delete(url) {
            header("Authorization", "Bearer $token")
            applyClientVersionHeaders()
            deviceId?.let { header("X-Device-Id", it) }
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status, runCatching { response.body<ApiError>().error }.getOrNull())
        }
    }

    private fun HttpRequestBuilder.applyClientVersionHeaders() {
        header("X-App-Version", AppRuntimeInfo.versionName)
        header("X-App-Build", AppRuntimeInfo.versionCode.toString())
        header(
            "X-App-Platform",
            when (AppRuntimeInfo.platform) {
                com.glagolitsa.model.ClientPlatform.ANDROID -> "android"
                com.glagolitsa.model.ClientPlatform.IOS -> "ios"
                com.glagolitsa.model.ClientPlatform.DESKTOP -> "desktop"
            },
        )
    }

    private fun encodeQueryParam(value: String): String = value.encodeURLParameter()
}

class ApiException(
    val status: HttpStatusCode,
    val errorMessage: String?,
) : Exception(errorMessage ?: "HTTP ${status.value}")

expect fun createHttpEngine(): io.ktor.client.engine.HttpClientEngineFactory<*>

/** Separate engine for realtime WS so REST connection pools stay healthy. */
expect fun createWebSocketHttpEngine(): io.ktor.client.engine.HttpClientEngineFactory<*>

expect fun defaultBaseUrl(): String
