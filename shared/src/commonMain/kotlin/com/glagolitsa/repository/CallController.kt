// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.repository

import com.glagolitsa.currentIsoTimestamp
import com.glagolitsa.currentTimeMillis
import com.glagolitsa.parseIsoTimestampMillis
import com.glagolitsa.api.ApiClient
import com.glagolitsa.audio.AudioProcessingCoordinator
import com.glagolitsa.call.CallMediaConnectOptions
import com.glagolitsa.call.CallMediaEngine
import com.glagolitsa.call.CallMediaEvent
import com.glagolitsa.call.CallMediaKeyManager
import com.glagolitsa.call.CallNetworkMode
import com.glagolitsa.call.CallQualityPolicy
import com.glagolitsa.call.CallQualitySample
import com.glagolitsa.call.createCallMediaEngine
import com.glagolitsa.crypto.CryptoEngine
import com.glagolitsa.log.AppLog
import com.glagolitsa.model.CallConnectionPolicy
import com.glagolitsa.model.CallControlEventData
import com.glagolitsa.model.CallFeaturePolicy
import com.glagolitsa.model.CallKeyOfferInput
import com.glagolitsa.model.CallRole
import com.glagolitsa.model.CallScope
import com.glagolitsa.model.CallSession
import com.glagolitsa.model.CallStatus
import com.glagolitsa.model.CallTokenResponse
import com.glagolitsa.model.CallType
import com.glagolitsa.model.CallUiState
import com.glagolitsa.model.CreateCallRequest
import com.glagolitsa.model.SubmitCallKeysRequest
import com.glagolitsa.model.WsEventType
import com.glagolitsa.model.isTerminal
import com.glagolitsa.model.partnerIdFor
import com.glagolitsa.platform.AppLifecycle
import com.glagolitsa.push.LocalMessageNotifier
import com.glagolitsa.session.SessionStore
import com.glagolitsa.session.SessionTokenProvider
import com.glagolitsa.ui.ClientFeatureFlags
import com.glagolitsa.util.decodeBase64
import com.glagolitsa.util.encodeBase64
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val LOCAL_PENDING_CALL_PREFIX = "local-call-"
private const val OUTGOING_CREATE_RECOVERY_WINDOW_MS = 90_000L

/**
 * Call control-plane dispatcher: API + local UI state.
 *
 * Media (LiveKit) connects separately via token; server never sees the stream.
 * WebSocket call.* events update state; polling remains fallback for accept wait.
 * [markCallConnected] only after real media path confirmation.
 */
class CallController(
    private val api: ApiClient,
    private val crypto: () -> CryptoEngine,
    private val usernameLookup: (String) -> String?,
    private val auth: SessionTokenProvider,
    private val mediaEngine: CallMediaEngine = createCallMediaEngine(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mediaKeys = CallMediaKeyManager()
    private val qualityPolicy = CallQualityPolicy()
    private var qualityJob: Job? = null
    private var activeCallSyncJob: Job? = null
    private var activeCallSyncCallId: String? = null
    private var ringingSyncJob: Job? = null
    private var incomingWatchJob: Job? = null
    private var connectJob: Job? = null
    private var createCallJob: Job? = null
    private val callStartMutex = Mutex()
    private var acceptedSignal: String? = null
    private var recentlyDismissedCall: RecentlyDismissedCall? = null

    private val _activeCall = MutableStateFlow<CallUiState?>(null)
    val activeCall: StateFlow<CallUiState?> = _activeCall.asStateFlow()

    private val _callHistory = MutableStateFlow<List<CallSession>>(emptyList())
    val callHistory: StateFlow<List<CallSession>> = _callHistory.asStateFlow()

    private val _networkMode = MutableStateFlow(CallNetworkMode.VOICE_ONLY)
    val networkMode: StateFlow<CallNetworkMode> = _networkMode.asStateFlow()

    /**
     * Handle privacy-safe call control WS events. Does not log tokens/keys.
     * Polling still runs as fallback if events are delayed.
     */
    fun onControlEvent(event: String, data: CallControlEventData) {
        val callId = data.call_id
        if (callId.isBlank()) return
        val active = _activeCall.value
        when (event) {
            WsEventType.CALL_RINGING, WsEventType.CALL_CREATED -> {
                // Never treat our own outbound session as an incoming ring (WS is
                // fanned out to all participants including the caller).
                if (data.status != null && data.status != CallStatus.RINGING) return
                AppLog.debug("call: ws $event id=$callId status=${data.status}")
                if (active != null &&
                    active.session.id != callId &&
                    !isLocalPendingCall(active.session.id) &&
                    !active.session.isTerminal()
                ) {
                    AppLog.debug("call: ignore ringing while another call is active")
                    return
                }
                scope.launch {
                    fetchCallSession(callId, "ws $event")?.let(::applyRingingSession)
                }
            }
            WsEventType.CALL_ACCEPTED, WsEventType.CALL_CONNECTED -> {
                if (active?.session?.id == callId) {
                    acceptedSignal = callId
                    data.status?.let { status ->
                        val nextSession = active.session.copy(status = status)
                        _activeCall.value = active.copy(
                            session = nextSession,
                            connectingMedia = if (status == CallStatus.ACTIVE) {
                                false
                            } else {
                                active.connectingMedia
                            },
                        )
                        if (status == CallStatus.ACTIVE) {
                            startActiveCallSync(callId)
                        }
                    }
                }
            }
            WsEventType.CALL_REJECTED, WsEventType.CALL_ENDED -> {
                LocalMessageNotifier.cancelIncomingCall(callId)
                if (active?.session?.id == callId) {
                    applyTerminalCall(
                        callId = callId,
                        session = active.session.copy(status = data.status ?: CallStatus.ENDED),
                    )
                }
            }
            WsEventType.CALL_LOW_BANDWIDTH -> {
                if (active?.session?.id == callId) {
                    _activeCall.value = active.copy(
                        lowBandwidthNotice = "Сеть слабая — сохраняем голос",
                    )
                }
            }
            WsEventType.CALL_ROUTE_DEGRADED -> {
                if (active?.session?.id == callId) {
                    _activeCall.value = active.copy(
                        lowBandwidthNotice = "Маршрут нестабилен. Сохраняем голос.",
                    )
                }
            }
        }
    }

    suspend fun refreshHistory(): List<CallSession> =
        auth.withAuth { token ->
            val history = api.listCallHistory(token)
            _callHistory.value = history
            val selfId = SessionStore.user.value?.id
            if (selfId != null && canSurfaceIncomingFromHistory()) {
                history.firstOrNull { it.status == CallStatus.RINGING && !wasJustDismissed(it.id) }
                    ?.let { ringing ->
                        applyRingingSession(ringing)
                    }
            }
            history
        }

    fun startIncomingWatch() {
        if (incomingWatchJob?.isActive == true) return
        incomingWatchJob = scope.launch {
            while (isActive) {
                delay(INCOMING_WATCH_INTERVAL_MS)
                if (!AppLifecycle.isInForeground()) continue
                if (!canSurfaceIncomingFromHistory()) continue
                runCatching { refreshHistory() }.onFailure {
                    AppLog.debug("call: incoming watch refresh failed error=${it.message}")
                }
            }
        }
    }

    fun stopIncomingWatch() {
        incomingWatchJob?.cancel()
        incomingWatchJob = null
    }

    suspend fun startOutgoingCall(
        calleeId: String,
        partnerName: String,
        callType: String = CallType.AUDIO,
    ): CallSession =
        callStartMutex.withLock {
            val current = _activeCall.value
            if (current != null && !current.session.isTerminal()) {
                error("Уже идет звонок")
            }
            val user = SessionStore.user.value ?: error("Not authenticated")
            val pendingSession = pendingOutgoingSession(
                callerId = user.id,
                calleeId = calleeId,
                callType = callType,
            )
            _activeCall.value = CallUiState(
                session = pendingSession,
                partnerName = partnerName,
                role = CallRole.Outgoing,
                lowBandwidthNotice = "Создаем защищенный звонок...",
            )
            AppLog.debug("call: outgoing local session shown id=${pendingSession.id}")
            createCallJob?.cancel()
            createCallJob = scope.launch {
                try {
                    // Feature kill switches are enforced server-side (503). UI also hides
                    // start-call controls via CallFeaturePolicy + ClientFeatureFlags.
                    AppLog.debug("call: outgoing start requested type=$callType")
                    // Cap create HTTP wait: server often commits + FCM in <100ms while the
                    // HTTP 201 write path still hangs on half-open mobile TLS.
                    val session = withTimeoutOrNull(CREATE_CALL_HTTP_TIMEOUT_MS) {
                        auth.withAuth { token ->
                            val deviceId = crypto().ensureDeviceIdentity(user.id).deviceId
                            AppLog.debug("call: create request type=$callType")
                            api.createCall(
                                token = token,
                                deviceId = deviceId,
                                request = CreateCallRequest(
                                    callee_id = calleeId,
                                    call_type = callType,
                                    device_id = deviceId,
                                ),
                            ).call
                        }
                    }
                    if (session != null) {
                        AppLog.debug("call: create ok status=${session.status}")
                        val latest = _activeCall.value
                        if (latest?.session?.id == pendingSession.id) {
                            _activeCall.value = latest.copy(
                                session = session,
                                lowBandwidthNotice = null,
                            )
                            launchConnectWhenReady(session.id)
                        }
                        return@launch
                    }
                    AppLog.warning("call: create HTTP slow/timeout — recovering via history")
                    // Primary path when response is lost: poll history while create may still finish.
                    var recovered: CallSession? = null
                    repeat(6) { attempt ->
                        recovered = recoverOutgoingAfterAmbiguousCreate(
                            pendingSession = pendingSession,
                            calleeId = calleeId,
                            callType = callType,
                        )
                        if (recovered != null) return@repeat
                        delay(400L * (attempt + 1))
                    }
                    recovered?.let {
                        AppLog.debug("call: create recovered id=${it.id} status=${it.status}")
                        val latest = _activeCall.value
                        if (latest?.session?.id == pendingSession.id) {
                            _activeCall.value = latest.copy(
                                session = it,
                                lowBandwidthNotice = null,
                            )
                            launchConnectWhenReady(it.id)
                        }
                        return@launch
                    }
                    val latest = _activeCall.value
                    if (latest?.session?.id == pendingSession.id) {
                        _activeCall.value = latest.copy(
                            session = latest.session.copy(
                                status = CallStatus.ENDED,
                                ended_at = currentIsoTimestamp(),
                            ),
                            lowBandwidthNotice = "Нет ответа от сервера звонков",
                        )
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    AppLog.warning("call: create failed type=$callType error=${t.message}")
                    // 409 "already active": may be our own ringing (timeout race) or inbound ring.
                    if ("another call is already active" in t.message.orEmpty().lowercase()) {
                        runCatching {
                            auth.withAuth { token ->
                                api.listCallHistory(token, limit = 8)
                                    .firstOrNull { !it.isTerminal() }
                            }
                        }.getOrNull()?.let { live ->
                            AppLog.debug("call: adopt live session after 409 id=${live.id} status=${live.status}")
                            applyRingingSession(live)
                            return@launch
                        }
                    }
                    var recovered = recoverOutgoingAfterAmbiguousCreate(
                        pendingSession = pendingSession,
                        calleeId = calleeId,
                        callType = callType,
                    )
                    if (recovered == null && isAmbiguousCreateFailure(t)) {
                        delay(800)
                        recovered = recoverOutgoingAfterAmbiguousCreate(
                            pendingSession = pendingSession,
                            calleeId = calleeId,
                            callType = callType,
                        )
                    }
                    recovered?.let {
                        AppLog.debug("call: create recovered id=${it.id} status=${it.status}")
                        val latest = _activeCall.value
                        if (latest?.session?.id == pendingSession.id) {
                            _activeCall.value = latest.copy(
                                session = it,
                                lowBandwidthNotice = null,
                            )
                            launchConnectWhenReady(it.id)
                        }
                        return@launch
                    }
                    val latest = _activeCall.value
                    if (latest?.session?.id == pendingSession.id) {
                        _activeCall.value = latest.copy(
                            session = latest.session.copy(
                                status = CallStatus.ENDED,
                                ended_at = currentIsoTimestamp(),
                            ),
                            lowBandwidthNotice = userVisibleCallError(t, "Не удалось начать звонок"),
                        )
                    }
                }
            }
            pendingSession
        }

    suspend fun startGroupCall(
        chatId: String,
        chatTitle: String,
        callType: String = CallType.AUDIO,
    ): CallSession =
        callStartMutex.withLock {
            val current = _activeCall.value
            if (current != null && !current.session.isTerminal()) {
                error("Уже идет звонок")
            }
            val user = SessionStore.user.value ?: error("Not authenticated")
            val pendingSession = CallSession(
                id = "$LOCAL_PENDING_CALL_PREFIX${Random.nextInt(0, Int.MAX_VALUE)}",
                caller_id = user.id,
                callee_id = "",
                chat_id = chatId,
                started_by_user_id = user.id,
                call_scope = CallScope.GROUP,
                call_type = callType,
                status = CallStatus.RINGING,
                livekit_room_id = "",
                created_at = currentIsoTimestamp(),
            )
            _activeCall.value = CallUiState(
                session = pendingSession,
                partnerName = chatTitle,
                role = CallRole.Outgoing,
                lowBandwidthNotice = "Создаем групповой звонок...",
            )
            try {
                val session = withTimeoutOrNull(CREATE_CALL_HTTP_TIMEOUT_MS) {
                    auth.withAuth { token ->
                        val deviceId = crypto().ensureDeviceIdentity(user.id).deviceId
                        api.createCall(
                            token = token,
                            deviceId = deviceId,
                            request = CreateCallRequest(
                                chat_id = chatId,
                                call_type = callType,
                                device_id = deviceId,
                                call_scope = CallScope.GROUP,
                            ),
                        ).call
                    }
                } ?: auth.withAuth { token -> api.getActiveCallForChat(token, chatId) }
                    ?: error("Нет ответа от сервера звонков")
                _activeCall.value = CallUiState(
                    session = session,
                    partnerName = chatTitle,
                    role = CallRole.Outgoing,
                    lowBandwidthNotice = null,
                )
                launchConnectWhenReady(session.id)
                session
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if ("another call is already active" in t.message.orEmpty().lowercase()) {
                    val live = runCatching {
                        auth.withAuth { token -> api.getActiveCallForChat(token, chatId) }
                    }.getOrNull()
                    if (live != null) {
                        applyRingingSession(live.copy(chat_id = live.chat_id ?: chatId))
                        return@withLock live
                    }
                }
                _activeCall.value = CallUiState(
                    session = pendingSession.copy(
                        status = CallStatus.ENDED,
                        ended_at = currentIsoTimestamp(),
                    ),
                    partnerName = chatTitle,
                    role = CallRole.Outgoing,
                    lowBandwidthNotice = userVisibleCallError(t, "Не удалось начать звонок"),
                )
                throw t
            }
        }

    suspend fun joinGroupCall(callId: String, chatTitle: String = "Групповой звонок"): CallSession {
        val user = SessionStore.user.value ?: error("Not authenticated")
        val joined = auth.withAuth { token ->
            val deviceId = crypto().ensureDeviceIdentity(user.id).deviceId
            api.joinCall(token, callId, deviceId).call
        }
        val role = if (joined.caller_id == user.id || joined.started_by_user_id == user.id) {
            CallRole.Outgoing
        } else {
            CallRole.Incoming
        }
        _activeCall.value = CallUiState(
            session = joined,
            partnerName = chatTitle,
            role = role,
            lowBandwidthNotice = null,
        )
        launchConnectWhenReady(joined.id)
        return joined
    }

    suspend fun activeCallForChat(chatId: String): CallSession? =
        auth.withAuth { token -> api.getActiveCallForChat(token, chatId) }

    private suspend fun recoverOutgoingAfterAmbiguousCreate(
        pendingSession: CallSession,
        calleeId: String,
        callType: String,
    ): CallSession? {
        if (!isLocalPendingCall(pendingSession.id)) return null
        val selfId = SessionStore.user.value?.id ?: return null
        return runCatching {
            auth.withAuth { token ->
                api.listCallHistory(token, limit = 12)
                    .firstOrNull { call ->
                        val starter = call.started_by_user_id?.takeIf { it.isNotBlank() }
                            ?: call.caller_id
                        val peerMatch = call.callee_id == calleeId ||
                            call.participants.any { it.user_id == calleeId }
                        starter == selfId &&
                            peerMatch &&
                            (call.call_type == callType || call.call_type.isBlank()) &&
                            !call.isTerminal() &&
                            isRecentRecoveredOutgoing(call)
                    }
            }
        }.getOrNull()
    }

    private fun isAmbiguousCreateFailure(t: Throwable): Boolean {
        val message = t.message.orEmpty().lowercase()
        return "another call is already active" in message ||
            "timeout" in message ||
            "request_timeout" in message ||
            "unable to resolve host" in message ||
            "connection" in message ||
            "http slow" in message
    }

    private fun isRecentRecoveredOutgoing(call: CallSession): Boolean {
        val createdMs = parseIsoTimestampMillis(call.created_at) ?: return true
        return currentTimeMillis() - createdMs <= OUTGOING_CREATE_RECOVERY_WINDOW_MS
    }

    private fun pendingOutgoingSession(
        callerId: String,
        calleeId: String,
        callType: String,
    ): CallSession =
        CallSession(
            id = "$LOCAL_PENDING_CALL_PREFIX${Random.nextInt(0, Int.MAX_VALUE)}",
            caller_id = callerId,
            callee_id = calleeId,
            call_type = callType,
            status = CallStatus.RINGING,
            livekit_room_id = "",
            created_at = currentIsoTimestamp(),
        )

    private fun isLocalPendingCall(callId: String): Boolean =
        callId.startsWith(LOCAL_PENDING_CALL_PREFIX)

    private fun markDismissed(callId: String) {
        recentlyDismissedCall = RecentlyDismissedCall(callId, currentTimeMillis())
    }

    private fun wasJustDismissed(callId: String): Boolean =
        recentlyDismissedCall?.suppresses(callId, currentTimeMillis()) == true

    private fun canSurfaceIncomingFromHistory(): Boolean =
        _activeCall.value?.session?.isTerminal() != false

    private suspend fun <T : Any> callActionOrNull(
        label: String,
        action: suspend (token: String) -> T,
    ): T? {
        var timedOut = false
        val result = runCatching {
            withTimeoutOrNull(CALL_ACTION_TIMEOUT_MS) {
                auth.withAuth { token -> action(token) }
            } ?: run {
                timedOut = true
                null
            }
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.warning("call: $label failed error=${it.message}")
        }.getOrNull()
        if (timedOut) {
            AppLog.warning("call: $label timed out")
        }
        return result
    }

    private suspend fun fetchCallSession(callId: String, label: String): CallSession? =
        callActionOrNull("$label getCall id=$callId") { token ->
            api.getCall(token, callId)
        }

    suspend fun acceptCall(callId: String): CallSession =
        try {
            LocalMessageNotifier.cancelIncomingCall(callId)
            // Optimistic local transition so Accept is not stuck on a 15s HTTP hang.
            val current = _activeCall.value?.takeIf { it.session.id == callId }
            if (current != null && current.session.status == CallStatus.RINGING) {
                _activeCall.value = current.copy(
                    session = current.session.copy(status = CallStatus.CONNECTING),
                    lowBandwidthNotice = null,
                )
            }
            val call = callActionOrNull("accept id=$callId") { token ->
                AppLog.debug("call: accept requested")
                val user = SessionStore.user.value ?: error("Not authenticated")
                val deviceId = crypto().ensureDeviceIdentity(user.id).deviceId
                api.acceptCall(token, callId, deviceId).call
            } ?: recoverCallState(
                callId = callId,
                label = "accept",
                accept = { CallConnectionPolicy.isAccepted(it) || it.isTerminal() },
            )
                ?: error("Нет ответа от сервера звонков")
            if (call.isTerminal()) {
                applyTerminalCall(callId, call)
            } else {
                updateActive(call)
                launchConnectWhenReady(callId)
            }
            call
        } catch (t: Throwable) {
            AppLog.warning("call: accept failed error=${t.message}")
            val terminal = recoverCallState(
                callId = callId,
                label = "accept terminal",
                accept = { it.isTerminal() },
            )
            if (terminal != null) {
                applyTerminalCall(callId, terminal)
            } else {
                _activeCall.value = _activeCall.value
                    ?.takeIf { it.session.id == callId }
                    ?.let { current ->
                        current.copy(
                            session = if (current.session.status == CallStatus.CONNECTING) {
                                current.session.copy(status = CallStatus.RINGING)
                            } else {
                                current.session
                            },
                            connectingMedia = false,
                            lowBandwidthNotice = userVisibleCallError(t, "Не удалось принять звонок"),
                        )
                    }
            }
            throw t
        }

    suspend fun rejectCall(callId: String): CallSession =
        try {
            LocalMessageNotifier.cancelIncomingCall(callId)
            stopRingingSync()
            // Local dismiss first — user must never wait full REST timeout to hang up ring.
            val rejectedLocal = rejectActiveCallLocally(callId)
            val call = requestRejectCall(callId)
                ?: recoverRejectedCall(callId, rejectedLocal)
                ?: error("Нет ответа от сервера звонков")
            _activeCall.value = _activeCall.value?.copy(session = call)
            delay(400)
            _activeCall.value = null
            scope.launch { runCatching { refreshHistory() } }
            call
        } catch (t: Throwable) {
            AppLog.warning("call: reject failed error=${t.message}")
            // Keep local reject if server failed — ring should not stick on device.
            if (_activeCall.value?.session?.id == callId) {
                _activeCall.value = null
            }
            throw t
        }

    private fun rejectActiveCallLocally(callId: String): CallSession? {
        val current = _activeCall.value?.takeIf { it.session.id == callId } ?: return null
        val rejected = current.session.copy(
            status = CallStatus.REJECTED,
            ended_at = currentIsoTimestamp(),
        )
        markDismissed(callId)
        _activeCall.value = current.copy(session = rejected)
        return rejected
    }

    private suspend fun requestRejectCall(callId: String): CallSession? =
        callActionOrNull("reject id=$callId") { token ->
            AppLog.debug("call: reject requested")
            api.rejectCall(token, callId).call
        }

    private suspend fun recoverRejectedCall(
        callId: String,
        localFallback: CallSession?,
    ): CallSession? {
        AppLog.warning("call: reject HTTP slow/timeout — retrying")
        repeat(CALL_RECOVERY_ATTEMPTS) { attempt ->
            val recovered = requestRejectCall(callId)
            if (recovered != null) {
                AppLog.debug("call: reject recovered id=$callId status=${recovered.status}")
                return recovered
            }
            delay(CALL_RECOVERY_BASE_DELAY_MS * (attempt + 1))
        }
        return localFallback
    }

    suspend fun endCall(callId: String): CallSession {
        LocalMessageNotifier.cancelIncomingCall(callId)
        if (isLocalPendingCall(callId)) {
            val ended = _activeCall.value
                ?.takeIf { it.session.id == callId }
                ?.session
                ?.copy(status = CallStatus.ENDED, ended_at = currentIsoTimestamp())
                ?: error("call not found")
            createCallJob?.cancel()
            createCallJob = null
            _activeCall.value = null
            scope.launch {
                endRecoveredPendingOutgoing(ended)
            }
            return ended
        }
        stopQualityLoop()
        stopActiveCallSync()
        stopRingingSync()
        createCallJob?.cancel()
        createCallJob = null
        connectJob?.cancel()
        connectJob = null
        runCatching { mediaEngine.disconnect() }
        mediaKeys.clear()
        val endedLocal = _activeCall.value
            ?.takeIf { it.session.id == callId }
            ?.session
            ?.copy(status = CallStatus.ENDED, ended_at = currentIsoTimestamp())
        if (endedLocal != null) {
            _activeCall.value = _activeCall.value?.copy(session = endedLocal)
        }
        return try {
            val call = endCallWithRecovery(callId) ?: endedLocal ?: error("Нет ответа от сервера звонков")
            _activeCall.value = _activeCall.value?.copy(session = call)
            delay(400)
            _activeCall.value = null
            scope.launch { runCatching { refreshHistory() } }
            call
        } catch (t: Throwable) {
            AppLog.warning("call: end failed error=${t.message}")
            _activeCall.value = null
            throw t
        }
    }

    fun rejectCallInBackground(callId: String) {
        scope.launch {
            runCatching { rejectCall(callId) }
        }
    }

    fun endCallInBackground(callId: String) {
        scope.launch {
            runCatching { endCall(callId) }
        }
    }

    private suspend fun endCallWithRecovery(callId: String): CallSession? {
        val direct = callActionOrNull("end id=$callId") { token ->
            AppLog.debug("call: end requested")
            val user = SessionStore.user.value
            val deviceId = user?.let { crypto().ensureDeviceIdentity(it.id).deviceId }
            api.endCall(token, callId, deviceId).call
        }
        if (direct != null) return direct

        AppLog.warning("call: end HTTP slow/timeout — recovering via call state")
        return recoverCallState(
            callId = callId,
            label = "end",
            accept = { it.isTerminal() },
        )
    }

    private suspend fun endRecoveredPendingOutgoing(pendingSession: CallSession) {
        if (!isLocalPendingCall(pendingSession.id)) return
        AppLog.debug("call: pending local end cleanup start id=${pendingSession.id}")
        repeat(8) { attempt ->
            val recovered = recoverOutgoingAfterAmbiguousCreate(
                pendingSession = pendingSession,
                calleeId = pendingSession.callee_id,
                callType = pendingSession.call_type,
            )
            if (recovered != null) {
                AppLog.debug("call: pending local end recovered remote id=${recovered.id} status=${recovered.status}")
                if (!recovered.isTerminal()) {
                    endCallWithRecovery(recovered.id)
                }
                return
            }
            delay(500L * (attempt + 1))
        }
        AppLog.warning("call: pending local end cleanup found no remote call id=${pendingSession.id}")
    }

    suspend fun fetchToken(callId: String): CallTokenResponse =
        auth.withAuth { token ->
            val user = SessionStore.user.value ?: error("Not authenticated")
            val deviceId = crypto().ensureDeviceIdentity(user.id).deviceId
            api.getCallToken(token, callId, deviceId)
        }

    suspend fun enableLowBandwidth(callId: String) {
        auth.withAuth { token ->
            val response = api.enableLowBandwidth(token, callId)
            _activeCall.value = _activeCall.value?.copy(
                session = response.call,
                lowBandwidthNotice = "Слабая сеть — сохраняем голос",
            )
        }
    }

    fun setMuted(muted: Boolean) {
        _activeCall.value = _activeCall.value?.copy(muted = muted)
    }

    suspend fun setMutedMedia(muted: Boolean) {
        setMuted(muted)
        runCatching { mediaEngine.setMicrophoneMuted(muted) }
    }

    fun setSpeaker(speakerOn: Boolean) {
        _activeCall.value = _activeCall.value?.copy(speakerOn = speakerOn)
    }

    suspend fun setSpeakerMedia(speakerOn: Boolean) {
        setSpeaker(speakerOn)
        runCatching { mediaEngine.setSpeaker(speakerOn) }
    }

    fun dismissCall() {
        val current = _activeCall.value ?: return
        if (current.session.isTerminal()) {
            stopRingingSync()
            _activeCall.value = null
        }
    }

    fun presentIncoming(session: CallSession) {
        applyRingingSession(session)
    }

    /**
     * Apply a ringing session from WS, push wake, or history poll.
     * Callers must not be flipped to [CallRole.Incoming] on their own outbound ring events.
     */
    private fun applyRingingSession(session: CallSession) {
        if (session.status != CallStatus.RINGING && !CallConnectionPolicy.isAccepted(session)) {
            return
        }
        val selfId = SessionStore.user.value?.id ?: return
        val starter = session.started_by_user_id?.takeIf { it.isNotBlank() } ?: session.caller_id
        val isOwnOutgoing = starter == selfId || session.caller_id == selfId

        val current = _activeCall.value
        if (isOwnOutgoing) {
            if (current == null || current.session.isTerminal()) {
                // App restart / process death while still ringing outbound.
                val partnerId = session.partnerIdFor(selfId)
                _activeCall.value = CallUiState(
                    session = session,
                    partnerName = usernameLookup(partnerId) ?: partnerId,
                    role = CallRole.Outgoing,
                )
                launchConnectWhenReady(session.id)
                return
            }
            if ((current.session.id == session.id || isLocalPendingCall(current.session.id)) &&
                current.role == CallRole.Outgoing
            ) {
                _activeCall.value = current.copy(
                    session = session,
                    lowBandwidthNotice = null,
                )
                if (isLocalPendingCall(current.session.id)) {
                    launchConnectWhenReady(session.id)
                }
            } else {
                AppLog.debug("call: ignore own outbound ringing as incoming")
            }
            return
        }

        if (wasJustDismissed(session.id)) {
            AppLog.debug("call: ignore recently dismissed ringing id=${session.id}")
            return
        }

        if (current != null &&
            current.session.id != session.id &&
            !isLocalPendingCall(current.session.id) &&
            !current.session.isTerminal()
        ) {
            AppLog.debug("call: ignore incoming while another call is active")
            return
        }
        val partnerId = session.partnerIdFor(selfId)
        val alreadyShowing = current?.session?.id == session.id && current.role == CallRole.Incoming
        _activeCall.value = CallUiState(
            session = session,
            partnerName = usernameLookup(partnerId) ?: partnerId,
            role = CallRole.Incoming,
        )
        startIncomingRingingSync(session.id)
        // WS path (app alive, possibly backgrounded) must still surface a local ring
        // when FCM is log-only / OEM-killed. Skip if already on the incoming UI.
        if (!alreadyShowing && !AppLifecycle.isInForeground()) {
            LocalMessageNotifier.showIncomingCall(session.id)
        }
    }

    suspend fun presentIncomingFromWake(callId: String) {
        if (callId.isBlank()) return
        AppLog.debug("call: incoming wake call_id=$callId")
        // Do not wait for device enroll storms — local notif already shown by push coordinator.
        val session = fetchCallSession(callId, "incoming wake") ?: return
        when {
            session.status == CallStatus.RINGING || CallConnectionPolicy.isAccepted(session) -> {
                applyRingingSession(session)
            }
            session.isTerminal() -> {
                LocalMessageNotifier.cancelIncomingCall(callId)
                applyTerminalCall(callId, session)
            }
        }
    }

    private fun launchConnectWhenReady(callId: String) {
        connectJob?.cancel()
        connectJob = scope.launch {
            connectWhenReady(callId)
        }
    }

    private suspend fun connectWhenReady(callId: String) {
        val state = _activeCall.value ?: return
        if (state.session.id != callId) return
        if (!CallConnectionPolicy.canStartMedia(state.session)) {
            return
        }

        // Outgoing calls ring first; media starts only after the callee accepts.
        if (state.role == CallRole.Outgoing) {
            val ready = waitUntilAccepted(callId)
            if (!ready) {
                val latest = _activeCall.value
                if (latest?.session?.id == callId && !latest.session.isTerminal()) {
                    AppLog.debug("call: outgoing timed out waiting for accept")
                    runCatching { endCall(callId) }
                        .onFailure {
                            _activeCall.value = latest.copy(
                                session = latest.session.copy(
                                    status = CallStatus.ENDED,
                                    ended_at = currentIsoTimestamp(),
                                ),
                                connectingMedia = false,
                                lowBandwidthNotice = "Абонент не ответил",
                            )
                        }
                }
                return
            }
        }

        val latestBeforeMedia = _activeCall.value ?: return
        if (latestBeforeMedia.session.id != callId) return
        if (!CallConnectionPolicy.canStartMedia(latestBeforeMedia.session)) return
        _activeCall.value = latestBeforeMedia.copy(connectingMedia = true)

        val livekitEnabled = CallFeaturePolicy.isLiveKitEnabled(ClientFeatureFlags.snapshot)
        val tokenResponse = runCatching { fetchToken(callId) }.getOrNull()
        var mediaConfirmed = false
        if (livekitEnabled && tokenResponse != null) {
            // E2EE strict: generate per-call media key before publish. Server only sees
            // encrypted offers via /keys (ciphertext). Never log the key.
            val e2eeRequired = tokenResponse.media_config.e2ee
            val e2eeKey = if (e2eeRequired) {
                resolveCallMediaKey(
                    callId = callId,
                    role = state.role,
                )
            } else {
                null
            }
            if (!e2eeRequired || e2eeKey != null) {
                mediaConfirmed = runCatching {
                    AppLog.debug("call: media connect start e2ee=$e2eeRequired")
                    withTimeoutOrNull(MEDIA_CONNECT_TIMEOUT_MS) {
                        mediaEngine.connect(
                            CallMediaConnectOptions(
                                token = tokenResponse.token,
                                url = tokenResponse.livekit_url,
                                roomName = tokenResponse.room_name,
                                participantId = tokenResponse.participant_id,
                                e2eeKey = e2eeKey,
                                audioOnly = true,
                                audioProcessing = AudioProcessingCoordinator.resolveCall(
                                    tokenResponse.media_config.audio,
                                ),
                            ),
                        )
                        mediaEngine.publishMicrophone()
                        // Wait briefly for media-path confirmation; do not mark connected on token alone.
                        mediaEngine.state.value.mediaPathConfirmed ||
                            withTimeoutOrNull(MEDIA_CONFIRM_TIMEOUT_MS) {
                                mediaEngine.events.first {
                                    it is CallMediaEvent.MediaPathConfirmed || it is CallMediaEvent.MicrophonePublished
                                }
                            } != null
                    } == true
                }.onFailure {
                    AppLog.warning("call: media connect failed error=${it.message}")
                }.getOrDefault(false)
                if (!mediaConfirmed) {
                    AppLog.warning("call: media connect timed out or was not confirmed")
                }
            } else {
                _activeCall.value = _activeCall.value
                    ?.takeIf { it.session.id == callId }
                    ?.copy(
                        connectingMedia = false,
                        lowBandwidthNotice = "Не удалось подготовить шифрование звонка",
                    )
            }
            if (mediaConfirmed) {
                startQualityLoop(
                    callId = callId,
                    routeClass = tokenResponse.route_class,
                )
            }
        }

        val latest = _activeCall.value
        if (latest == null || !CallConnectionPolicy.canMarkConnected(callId, latest.session)) {
            return
        }
        if (livekitEnabled && !mediaConfirmed) {
            failMediaConnection(callId, "Не удалось подключить звонок")
            return
        }

        val connectedCall = markConnectedWithRecovery(callId)
        if (connectedCall == null) {
            failMediaConnection(callId, "Не удалось подтвердить звонок")
            return
        }
        if (connectedCall.isTerminal()) {
            stopQualityLoop()
            stopActiveCallSync()
            runCatching { mediaEngine.disconnect() }
            mediaKeys.clear()
            _activeCall.value = null
            return
        }
        _activeCall.value = _activeCall.value
            ?.takeIf { it.session.id == callId }
            ?.copy(
                session = connectedCall,
                connectingMedia = false,
            )
        if (connectedCall.status == CallStatus.ACTIVE) {
            startActiveCallSync(callId)
        }
    }

    private suspend fun markConnectedWithRecovery(callId: String): CallSession? {
        val user = SessionStore.user.value ?: return null
        val deviceId = crypto().ensureDeviceIdentity(user.id).deviceId
        val direct = callActionOrNull("connected id=$callId") { token ->
            api.markCallConnected(token, callId, deviceId).call
        }
        if (direct != null) return direct

        AppLog.warning("call: connected HTTP slow/timeout — recovering via call state")
        return recoverCallState(
            callId = callId,
            label = "connected",
            accept = { it.status == CallStatus.ACTIVE || it.isTerminal() },
        )
    }

    private suspend fun recoverCallState(
        callId: String,
        label: String,
        accept: (CallSession) -> Boolean,
    ): CallSession? {
        repeat(CALL_RECOVERY_ATTEMPTS) { attempt ->
            val recovered = fetchCallSession(callId, "$label recovery")
            if (recovered != null) {
                if (accept(recovered)) {
                    AppLog.debug("call: $label recovered id=$callId status=${recovered.status}")
                    return recovered
                }
                if (_activeCall.value?.session?.id == callId) {
                    updateActive(recovered)
                }
            }
            delay(CALL_RECOVERY_BASE_DELAY_MS * (attempt + 1))
        }
        return null
    }

    private fun failMediaConnection(callId: String, notice: String) {
        val latest = _activeCall.value ?: return
        if (latest.session.id != callId || latest.session.isTerminal()) return
        stopQualityLoop()
        stopActiveCallSync()
        scope.launch { runCatching { mediaEngine.disconnect() } }
        _activeCall.value = latest.copy(
            session = latest.session.copy(
                status = CallStatus.ENDED,
                ended_at = currentIsoTimestamp(),
            ),
            connectingMedia = false,
            lowBandwidthNotice = notice,
        )
        scope.launch {
            runCatching { endCall(callId) }
        }
    }

    private fun startQualityLoop(callId: String, routeClass: String?) {
        stopQualityLoop()
        qualityJob = scope.launch {
            var lowBandwidthNotified = false
            while (isActive) {
                delay(2_000)
                val snap = mediaEngine.stats.value
                val sample = CallQualitySample(
                    connectionQuality = snap?.connectionQuality,
                    rttMs = snap?.rttMs,
                    packetLossPercent = snap?.packetLossPercent,
                    jitterMs = snap?.jitterMs,
                    candidateType = snap?.candidateType,
                    candidateProtocol = snap?.candidateProtocol,
                    routeClass = routeClass,
                )
                val decision = qualityPolicy.evaluate(sample)
                _networkMode.value = decision.mode
                val notice = decision.notice
                if (!notice.isNullOrBlank()) {
                    val cur = _activeCall.value
                    if (cur?.session?.id == callId) {
                        _activeCall.value = cur.copy(lowBandwidthNotice = notice)
                    }
                }
                if (!lowBandwidthNotified &&
                    decision.mode == CallNetworkMode.EMERGENCY_VOICE
                ) {
                    lowBandwidthNotified = true
                    runCatching {
                        auth.withAuth { token -> api.enableLowBandwidth(token, callId) }
                    }
                }
            }
        }
    }

    private fun startActiveCallSync(callId: String) {
        if (activeCallSyncCallId == callId && activeCallSyncJob?.isActive == true) {
            return
        }
        stopActiveCallSync()
        activeCallSyncCallId = callId
        activeCallSyncJob = scope.launch {
            while (isActive) {
                delay(ACTIVE_CALL_SYNC_INTERVAL_MS)
                val current = _activeCall.value ?: return@launch
                if (current.session.id != callId || current.session.isTerminal()) {
                    return@launch
                }
                val refreshed = fetchCallSession(callId, "active call sync") ?: continue
                if (refreshed.isTerminal()) {
                    AppLog.debug("call: active call sync observed terminal id=$callId status=${refreshed.status}")
                    applyTerminalCall(callId, refreshed)
                    return@launch
                }
                if (refreshed.status != current.session.status ||
                    refreshed.connected_at != current.session.connected_at
                ) {
                    updateActive(refreshed)
                }
            }
        }
    }

    private fun stopActiveCallSync() {
        activeCallSyncJob?.cancel()
        activeCallSyncJob = null
        activeCallSyncCallId = null
    }

    private fun stopQualityLoop() {
        qualityJob?.cancel()
        qualityJob = null
    }

    private fun startIncomingRingingSync(callId: String) {
        ringingSyncJob?.cancel()
        AppLog.debug("call: incoming ringing sync start id=$callId")
        ringingSyncJob = scope.launch {
            while (isActive) {
                delay(2_000)
                val current = _activeCall.value ?: return@launch
                if (current.session.id != callId || current.role != CallRole.Incoming) return@launch
                if (current.session.status != CallStatus.RINGING) return@launch
                val refreshed = fetchCallSession(callId, "incoming ringing sync") ?: continue
                when {
                    refreshed.isTerminal() -> {
                        AppLog.debug("call: incoming ringing sync observed terminal id=$callId status=${refreshed.status}")
                        applyTerminalCall(callId, refreshed)
                        return@launch
                    }
                    CallConnectionPolicy.isAccepted(refreshed) -> {
                        updateActive(refreshed)
                        return@launch
                    }
                    refreshed.status == CallStatus.RINGING -> updateActive(refreshed)
                }
            }
        }
    }

    private fun stopRingingSync() {
        ringingSyncJob?.cancel()
        ringingSyncJob = null
    }

    private fun applyTerminalCall(callId: String, session: CallSession) {
        LocalMessageNotifier.cancelIncomingCall(callId)
        markDismissed(callId)
        val active = _activeCall.value ?: return
        if (active.session.id != callId) return
        stopRingingSync()
        stopQualityLoop()
        stopActiveCallSync()
        scope.launch { runCatching { mediaEngine.disconnect() } }
        mediaKeys.clear()
        _activeCall.value = active.copy(
            session = session,
            connectingMedia = false,
            lowBandwidthNotice = if (session.status == CallStatus.REJECTED) {
                null
            } else {
                active.lowBandwidthNotice
            },
        )
        scope.launch {
            delay(600)
            if (_activeCall.value?.session?.id == callId) {
                _activeCall.value = null
            }
        }
    }

    private suspend fun resolveCallMediaKey(
        callId: String,
        role: CallRole,
    ): ByteArray? =
        auth.withAuth { token ->
            val user = SessionStore.user.value ?: return@withAuth null
            val ownDeviceId = crypto().ensureDeviceIdentity(user.id).deviceId
            when (role) {
                CallRole.Outgoing -> {
                    val key = mediaKeys.activeKeyOrNull()
                        ?: mediaKeys.generateKey { size ->
                            ByteArray(size) { Random.nextInt(0, 256).toByte() }
                        }
                    val session = runCatching { api.getCall(token, callId) }.getOrNull()
                        ?: _activeCall.value?.session
                        ?: return@withAuth null
                    if (_activeCall.value?.session?.id == callId) {
                        updateActive(session)
                    }
                    if (!submitCallMediaKeyOffers(token, session, user.id, ownDeviceId, key)) {
                        return@withAuth null
                    }
                    key
                }
                CallRole.Incoming -> {
                    mediaKeys.activeKeyOrNull()
                        ?: waitForIncomingCallMediaKey(token, callId, user.id, ownDeviceId)
                }
            }
        }

    private suspend fun submitCallMediaKeyOffers(
        token: String,
        session: CallSession,
        userId: String,
        ownDeviceId: String,
        key: ByteArray,
    ): Boolean {
        val targets = session.participants
            .filter { mediaKeys.isEligibleTarget(it.invite_state, left = !it.left_at.isNullOrBlank()) }
            .filter { it.user_id != userId && !it.device_id.isNullOrBlank() }
            .distinctBy { it.user_id to it.device_id }
        if (targets.isEmpty()) {
            AppLog.warning("call: no eligible media key targets")
            return false
        }
        val offers = targets.mapNotNull { target ->
            val targetDeviceId = target.device_id ?: return@mapNotNull null
            val bundle = runCatching { api.getDeviceBundle(token, targetDeviceId) }.getOrNull()
                ?: return@mapNotNull null
            val sessionReady = crypto().ensureSession(
                accountId = userId,
                remoteAccountId = target.user_id,
                remoteDeviceId = targetDeviceId,
                bundle = bundle,
            )
            if (!sessionReady) return@mapNotNull null
            val encrypted = crypto().encryptMessage(
                accountId = userId,
                remoteAccountId = target.user_id,
                remoteDeviceId = targetDeviceId,
                plaintext = key,
            ) ?: return@mapNotNull null
            CallKeyOfferInput(
                target_user_id = target.user_id,
                target_device_id = targetDeviceId,
                envelope_type = encrypted.envelopeType,
                encrypted_key = encrypted.ciphertext.encodeBase64(),
            )
        }
        if (offers.isEmpty()) {
            AppLog.warning("call: media key encryption produced no offers")
            return false
        }
        runCatching {
            api.submitCallKeys(
                token = token,
                callId = session.id,
                deviceId = ownDeviceId,
                request = SubmitCallKeysRequest(offers = offers),
            )
        }.onFailure {
            AppLog.warning("call: submit media key offers failed error=${it.message}")
            return false
        }
        AppLog.debug("call: submitted media key offers count=${offers.size}")
        return true
    }

    private suspend fun waitForIncomingCallMediaKey(
        token: String,
        callId: String,
        userId: String,
        ownDeviceId: String,
    ): ByteArray? {
        repeat(12) {
            val offers = runCatching { api.listCallKeys(token, callId, ownDeviceId) }
                .getOrDefault(emptyList())
            for (offer in offers) {
                val ciphertext = runCatching { offer.encrypted_key.decodeBase64() }.getOrNull()
                    ?: continue
                val key = crypto().decryptMessage(
                    accountId = userId,
                    remoteAccountId = offer.source_user_id,
                    remoteDeviceUuid = offer.source_device_id,
                    envelopeType = offer.envelope_type,
                    ciphertext = ciphertext,
                ) ?: continue
                if (key.size >= 16) {
                    mediaKeys.setActiveKey(key)
                    AppLog.debug("call: received media key offer")
                    return key
                }
            }
            delay(500)
        }
        AppLog.warning("call: media key offer timed out")
        return null
    }

    /**
     * @return true when the call reached CONNECTING/ACTIVE and is still the active UI call.
     * false when cancelled, rejected, missed, or timed out — caller must not mark connected.
     */
    private suspend fun waitUntilAccepted(callId: String): Boolean {
        acceptedSignal = null
        // Prefer WS acceptedSignal; sparse REST poll only as fallback (was 1Hz → load + hangs).
        var polls = 0
        val deadlineMs = currentTimeMillis() + 45_000L
        while (currentTimeMillis() < deadlineMs) {
            val current = _activeCall.value?.session ?: return false
            if (current.id != callId) return false
            if (CallConnectionPolicy.isAccepted(current) || acceptedSignal == callId) {
                return true
            }
            if (current.isTerminal()) {
                applyTerminalCall(callId, current)
                return false
            }
            delay(500)
            // Poll REST every ~3s, not every second.
            polls++
            if (polls % 6 != 0) continue
            val refreshed = fetchCallSession(callId, "accept wait") ?: continue
            if (_activeCall.value?.session?.id == callId) {
                updateActive(refreshed)
            }
            if (refreshed.isTerminal()) {
                applyTerminalCall(callId, refreshed)
                return false
            }
            if (CallConnectionPolicy.isAccepted(refreshed)) return true
        }
        return false
    }

    private data class RecentlyDismissedCall(
        val id: String,
        val dismissedAtMs: Long,
    ) {
        fun suppresses(callId: String, nowMs: Long): Boolean =
            id == callId && nowMs - dismissedAtMs < DISMISS_SUPPRESS_MS
    }

    companion object {
        /** Keep accept/reject/end under UI responsiveness budget (not full REST 15s). */
        const val CALL_ACTION_TIMEOUT_MS = 3_500L
        /** Fail create HTTP early and recover from history (server often already ringing). */
        const val CREATE_CALL_HTTP_TIMEOUT_MS = 4_000L
        const val INCOMING_WATCH_INTERVAL_MS = 2_000L
        const val DISMISS_SUPPRESS_MS = 20_000L
        /** Bound the whole SDK connect/publish path; some transports can suspend indefinitely. */
        const val MEDIA_CONNECT_TIMEOUT_MS = 12_000L
        const val MEDIA_CONFIRM_TIMEOUT_MS = 8_000L
        const val ACTIVE_CALL_SYNC_INTERVAL_MS = 2_000L
        const val CALL_RECOVERY_ATTEMPTS = 4
        const val CALL_RECOVERY_BASE_DELAY_MS = 250L
    }

    private fun updateActive(session: CallSession) {
        val current = _activeCall.value ?: return
        if (session.isTerminal()) {
            applyTerminalCall(session.id, session)
            return
        }
        if (session.status == CallStatus.ACTIVE) {
            startActiveCallSync(session.id)
        }
        _activeCall.value = current.copy(
            session = session,
            connectingMedia = if (session.status == CallStatus.ACTIVE) {
                false
            } else {
                current.connectingMedia
            },
        )
    }

    private fun userVisibleCallError(t: Throwable, fallback: String): String {
        val message = t.message.orEmpty()
        val lower = message.lowercase()
        return when {
            "another call is already active" in lower ->
                "Сейчас нельзя начать звонок: уже есть активный вызов. Подождите и попробуйте снова."
            "request_timeout" in lower || "timeout" in lower ->
                "Нет ответа от сервера звонков"
            message.isNotBlank() -> message
            else -> fallback
        }
    }
}
