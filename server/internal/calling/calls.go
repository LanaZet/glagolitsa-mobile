// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"

	"github.com/google/uuid"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/notification"
	"glagolitsa/server/internal/store"
)

func (h *Handler) CreateCall(w http.ResponseWriter, r *http.Request) {
	start := store.NowUTC()
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	// Stale sweep is owned by the jobs worker (TypeCallTimeoutSweep). Do not block
	// create ACK on a full table scan — client create timeouts left "201 missing" in logs.
	slog.Info("call_create_start", "user_id", claims.UserID)

	var req model.CreateCallRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}

	callType := strings.TrimSpace(req.CallType)
	if callType == "" {
		callType = model.CallTypeAudio
	}
	if callType != model.CallTypeAudio && callType != model.CallTypeVideo {
		httpx.WriteError(w, http.StatusBadRequest, "call_type must be audio or video")
		return
	}
	if ok, reason := h.Features.AllowsNewCall(callType); !ok {
		// 503: operational / feature kill switch, not a client bug.
		httpx.WriteError(w, http.StatusServiceUnavailable, reason)
		return
	}

	// Normalize to group-ready shape. Legacy clients only send callee_id.
	chatID := strings.TrimSpace(req.ChatID)
	calleeID := strings.TrimSpace(req.CalleeID)
	inviteeIDs := normalizeInviteeIDs(req.InitialInviteeIDs, claims.UserID)
	callScope := strings.TrimSpace(req.CallScope)

	if chatID != "" {
		if !h.Features.GroupReadyAPI {
			httpx.WriteError(w, http.StatusServiceUnavailable, "group-ready calls are disabled")
			return
		}
		if err := NewGroupCallService(h.Store).AuthorizeStart(chatID, claims.UserID); err != nil {
			writeCallError(w, err)
			return
		}
		if callScope == "" {
			callScope = model.CallScopeGroup
		}
		if len(inviteeIDs) == 0 && calleeID != "" {
			inviteeIDs = []string{calleeID}
		}
		// Empty invitees: open group room. Members join via link / POST .../join.
	} else {
		// DM wrapper: callee_id → scope=dm + one invitee.
		if calleeID == "" {
			httpx.WriteError(w, http.StatusBadRequest, "callee_id is required")
			return
		}
		if calleeID == claims.UserID {
			httpx.WriteError(w, http.StatusBadRequest, "cannot call yourself")
			return
		}
		callScope = model.CallScopeDM
		inviteeIDs = []string{calleeID}
	}

	callID := uuid.NewString()
	now := store.NowUTC()
	deviceID := strings.TrimSpace(req.DeviceID)
	// Legacy denormalized fields kept for older clients/history.
	legacyCallee := ""
	if len(inviteeIDs) == 1 {
		legacyCallee = inviteeIDs[0]
	}
	session := model.CallSession{
		ID:              callID,
		CallerID:        claims.UserID,
		CalleeID:        legacyCallee,
		ChatID:          chatID,
		StartedByUserID: claims.UserID,
		CallScope:       callScope,
		CallType:        callType,
		Status:          model.CallStatusRinging,
		LivekitRoomID:   h.Rooms.RoomName(callID),
		CallerDeviceID:  deviceID,
		SelectedRegion:  h.RegionID,
		RouteClass:      "single_region",
		PolicyVersion:   1,
		CreatedAt:       now,
	}

	liveGuardUserIDs := append([]string{claims.UserID}, inviteeIDs...)
	// Drop caller's own abandoned ringing sessions (client timeout / lost response)
	// so a retry is not permanently blocked by live-guard. Keep off the critical path
	// when possible — still needed for live-guard correctness on rapid redial.
	supersedeStart := store.NowUTC()
	h.supersedeOwnAbandonedRinging(claims.UserID, inviteeIDs)
	supersedeMs := store.NowUTC().Sub(supersedeStart).Milliseconds()

	dbStart := store.NowUTC()
	created, err := h.Store.CreateCallIfAvailable(session, liveGuardUserIDs)
	dbMs := store.NowUTC().Sub(dbStart).Milliseconds()
	if err != nil {
		if errors.Is(err, store.ErrAlreadyExists) {
			// Idempotent create: if our previous attempt still rings the same peers,
			// return that session instead of 409 (fixes timeout → retry loops).
			if existing, ok := h.reclaimOwnOutgoingRinging(claims.UserID, inviteeIDs, callType); ok {
				existing = h.withCallParticipants(existing)
				h.notifyIncomingCallAsync(claims.UserID, existing, inviteeIDs)
				h.publishCallEvent(EventCallCreated, existing, "")
				h.publishCallEvent(EventCallRinging, existing, "")
				if r.Context().Err() != nil {
					slog.Warn("call_create_client_gone", "call_id", existing.ID, "phase", "reclaim")
					return
				}
				httpx.WriteJSON(w, http.StatusCreated, model.CallActionResponse{Call: existing})
				slog.Info("call_create_ok",
					"call_id", existing.ID,
					"reclaim", true,
					"db_ms", dbMs,
					"supersede_ms", supersedeMs,
					"total_ms", store.NowUTC().Sub(start).Milliseconds(),
				)
				return
			}
			httpx.WriteError(w, http.StatusConflict, "another call is already active")
			return
		}
		if errors.Is(err, store.ErrBusy) {
			httpx.WriteError(w, http.StatusConflict, "another call is already active")
			return
		}
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	_ = h.Store.UpsertCallParticipant(model.CallParticipant{
		CallID:      callID,
		UserID:      claims.UserID,
		DeviceID:    deviceID,
		Role:        model.CallParticipantRoleStarter,
		InviteState: model.CallInviteStateJoined,
		MediaState:  model.CallMediaAudioOnly,
		JoinedAt:    &now,
	})
	for _, invitee := range inviteeIDs {
		_ = h.Store.UpsertCallParticipant(model.CallParticipant{
			CallID:      callID,
			UserID:      invitee,
			DeviceID:    "",
			Role:        model.CallParticipantRoleInvited,
			InviteState: model.CallInviteStateRinging,
			MediaState:  model.CallMediaAudioOnly,
		})
		_, _ = h.Store.CreateCallInvite(model.CallInvite{
			CallID:          callID,
			InvitedUserID:   invitee,
			InvitedByUserID: claims.UserID,
			State:           model.CallInvitePending,
			CreatedAt:       now,
		})
	}

	// Push is opaque (ids only). Run async so FCM/APNs latency cannot delay HTTP 201
	// past the client create timeout (which leaves a stuck live call + 409 on retry).
	h.notifyIncomingCallAsync(claims.UserID, created, inviteeIDs)

	created = h.withCallParticipants(created)
	// Fan-out after body is ready; still before write so callee WS gets ring ASAP.
	h.publishCallEvent(EventCallCreated, created, "")
	h.publishCallEvent(EventCallRinging, created, "")

	if r.Context().Err() != nil {
		// Client already timed out — call + push exist; do not block on a dead write.
		slog.Warn("call_create_client_gone",
			"call_id", created.ID,
			"db_ms", dbMs,
			"total_ms", store.NowUTC().Sub(start).Milliseconds(),
		)
		return
	}
	httpx.WriteJSON(w, http.StatusCreated, model.CallActionResponse{Call: created})
	slog.Info("call_create_ok",
		"call_id", created.ID,
		"reclaim", false,
		"db_ms", dbMs,
		"supersede_ms", supersedeMs,
		"total_ms", store.NowUTC().Sub(start).Milliseconds(),
		"invitees", len(inviteeIDs),
	)
}

func (h *Handler) notifyIncomingCallAsync(callerID string, call model.CallSession, inviteeIDs []string) {
	if h.Notifier == nil {
		return
	}
	for _, invitee := range inviteeIDs {
		inviteeID := invitee
		payload := notification.IncomingCallPayload{
			CallID:      call.ID,
			CallerID:    callerID,
			CallType:    call.CallType,
			LivekitRoom: call.LivekitRoomID,
		}
		go func() {
			_ = h.Notifier.NotifyIncomingCall(inviteeID, payload)
		}()
	}
}

// reclaimOwnOutgoingRinging returns a still-ringing call started by userID to the same
// invitees (same peer set). Used for idempotent create after client timeout/retry.
func (h *Handler) reclaimOwnOutgoingRinging(userID string, inviteeIDs []string, callType string) (model.CallSession, bool) {
	calls, err := h.Store.ListCallsForUser(userID, 20)
	if err != nil {
		return model.CallSession{}, false
	}
	want := map[string]struct{}{}
	for _, id := range inviteeIDs {
		want[id] = struct{}{}
	}
	for _, call := range calls {
		if call.Status != model.CallStatusRinging {
			continue
		}
		starter := strings.TrimSpace(call.StartedByUserID)
		if starter == "" {
			starter = call.CallerID
		}
		if starter != userID {
			continue
		}
		if callType != "" && call.CallType != "" && call.CallType != callType {
			continue
		}
		if callMatchesInvitees(call, want, h) {
			return call, true
		}
	}
	return model.CallSession{}, false
}

// supersedeOwnAbandonedRinging ends the caller's other ringing sessions that do not
// target the same invitees, so a new dial is not blocked by a ghost outbound ring.
func (h *Handler) supersedeOwnAbandonedRinging(userID string, keepInviteeIDs []string) {
	calls, err := h.Store.ListCallsForUser(userID, 20)
	if err != nil {
		return
	}
	keep := map[string]struct{}{}
	for _, id := range keepInviteeIDs {
		keep[id] = struct{}{}
	}
	now := store.NowUTC()
	for _, call := range calls {
		if call.Status != model.CallStatusRinging {
			continue
		}
		starter := strings.TrimSpace(call.StartedByUserID)
		if starter == "" {
			starter = call.CallerID
		}
		if starter != userID {
			continue
		}
		if callMatchesInvitees(call, keep, h) {
			// Same peer: keep for possible reclaim after CreateCallIfAvailable conflict.
			continue
		}
		updated, err := h.Store.UpdateCallStatus(call.ID, model.CallStatusMissed, now)
		if err != nil {
			slog.Debug("supersede own ringing failed", "call_id", call.ID, "err", err.Error())
			continue
		}
		h.applyCallPresence(updated, false)
		h.publishCallEvent(EventCallEnded, updated, "")
	}
}

func callMatchesInvitees(call model.CallSession, invitees map[string]struct{}, h *Handler) bool {
	if len(invitees) == 0 {
		return false
	}
	if call.CalleeID != "" {
		if _, ok := invitees[call.CalleeID]; ok && len(invitees) == 1 {
			return true
		}
	}
	matched := 0
	if parts, err := h.Store.ListCallParticipants(call.ID); err == nil {
		for _, p := range parts {
			if _, ok := invitees[p.UserID]; ok {
				matched++
			}
		}
	}
	return matched > 0 && matched >= len(invitees)
}

func normalizeInviteeIDs(ids []string, selfID string) []string {
	seen := map[string]struct{}{}
	out := make([]string, 0, len(ids))
	for _, raw := range ids {
		id := strings.TrimSpace(raw)
		if id == "" || id == selfID {
			continue
		}
		if _, ok := seen[id]; ok {
			continue
		}
		seen[id] = struct{}{}
		out = append(out, id)
	}
	return out
}

func (h *Handler) GetCall(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	call, err := h.loadAuthorizedCall(r, claims.UserID)
	if err != nil {
		writeCallError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, h.withCallParticipants(call))
}

func (h *Handler) ListCallHistory(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	limit := 50
	if raw := r.URL.Query().Get("limit"); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 && parsed <= 100 {
			limit = parsed
		}
	}

	calls, err := h.Store.ListCallsForUser(claims.UserID, limit)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if calls == nil {
		calls = []model.CallSession{}
	}
	for i := range calls {
		calls[i] = h.withCallParticipants(calls[i])
	}
	httpx.WriteJSON(w, http.StatusOK, calls)
}

func (h *Handler) AcceptCall(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	call, err := h.loadAuthorizedCall(r, claims.UserID)
	if err != nil {
		writeCallError(w, err)
		return
	}
	// Participant-centric accept: starter is already joined; invitees accept.
	if call.CallerID == claims.UserID || call.StartedByUserID == claims.UserID {
		httpx.WriteError(w, http.StatusForbidden, "starter cannot accept own call")
		return
	}
	deviceID := strings.TrimSpace(r.Header.Get("X-Device-Id"))
	now := store.NowUTC()

	// Idempotent: already connecting/active returns current state (multi-device).
	if call.Status == model.CallStatusConnecting || call.Status == model.CallStatusActive {
		_ = h.Store.UpsertCallParticipant(model.CallParticipant{
			CallID:      call.ID,
			UserID:      claims.UserID,
			DeviceID:    deviceID,
			Role:        model.CallParticipantRoleJoined,
			InviteState: model.CallInviteStateAccepted,
			MediaState:  model.CallMediaAudioOnly,
			JoinedAt:    &now,
		})
		httpx.WriteJSON(w, http.StatusOK, model.CallActionResponse{Call: h.withCallParticipants(call)})
		return
	}
	if call.Status != model.CallStatusRinging {
		httpx.WriteError(w, http.StatusConflict, "call is not ringing")
		return
	}

	updated, err := h.Store.UpdateCallStatus(call.ID, model.CallStatusConnecting, now)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	_ = h.Store.UpsertCallParticipant(model.CallParticipant{
		CallID:      call.ID,
		UserID:      claims.UserID,
		DeviceID:    deviceID,
		Role:        model.CallParticipantRoleJoined,
		InviteState: model.CallInviteStateAccepted,
		MediaState:  model.CallMediaAudioOnly,
		JoinedAt:    &now,
	})
	updated = h.withCallParticipants(updated)
	h.publishCallEvent(EventCallAccepted, updated, claims.UserID)
	httpx.WriteJSON(w, http.StatusOK, model.CallActionResponse{Call: updated})
}

func (h *Handler) RejectCall(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	call, err := h.loadAuthorizedCall(r, claims.UserID)
	if err != nil {
		writeCallError(w, err)
		return
	}
	if call.CallerID == claims.UserID || call.StartedByUserID == claims.UserID {
		httpx.WriteError(w, http.StatusForbidden, "starter cannot reject own call")
		return
	}

	now := store.NowUTC()
	updated, err := h.Store.UpdateCallStatus(call.ID, model.CallStatusRejected, now)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	deviceID := strings.TrimSpace(r.Header.Get("X-Device-Id"))
	_ = h.Store.UpsertCallParticipant(model.CallParticipant{
		CallID:      call.ID,
		UserID:      claims.UserID,
		DeviceID:    deviceID,
		Role:        model.CallParticipantRoleInvited,
		InviteState: model.CallInviteStateRejected,
		MediaState:  model.CallMediaAudioOnly,
		LeftAt:      &now,
	})
	updated = h.withCallParticipants(updated)
	h.applyCallPresence(updated, false)
	h.publishCallEvent(EventCallRejected, updated, claims.UserID)
	httpx.WriteJSON(w, http.StatusOK, model.CallActionResponse{Call: updated})
}

func (h *Handler) EndCall(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	call, err := h.loadAuthorizedCall(r, claims.UserID)
	if err != nil {
		writeCallError(w, err)
		return
	}
	// Already terminal: still clear in_call so a late end after a raced
	// mark-connected (or a second hangup) cannot leave presence stuck.
	if call.Status == model.CallStatusEnded || call.Status == model.CallStatusRejected || call.Status == model.CallStatusMissed {
		h.applyCallPresence(call, false)
		httpx.WriteJSON(w, http.StatusOK, model.CallActionResponse{Call: call})
		return
	}

	now := store.NowUTC()
	updated, err := h.Store.EndCall(call.ID, now)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	deviceID := strings.TrimSpace(r.Header.Get("X-Device-Id"))
	_ = h.Store.UpsertParticipant(call.ID, claims.UserID, deviceID, nil, &now)

	updated = h.withCallParticipants(updated)
	h.applyCallPresence(updated, false)
	h.publishCallEvent(EventCallEnded, updated, claims.UserID)
	httpx.WriteJSON(w, http.StatusOK, model.CallActionResponse{Call: updated})
}

func (h *Handler) MarkConnected(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	call, err := h.loadAuthorizedCall(r, claims.UserID)
	if err != nil {
		writeCallError(w, err)
		return
	}

	// Never resurrect a cancelled/ended call into ACTIVE + in_call presence.
	// Outgoing clients used to race endCall vs markCallConnected after hangup.
	if call.Status == model.CallStatusEnded || call.Status == model.CallStatusRejected || call.Status == model.CallStatusMissed {
		h.applyCallPresence(call, false)
		httpx.WriteError(w, http.StatusConflict, "call already ended")
		return
	}

	// Prefer explicit media-path confirmation. Empty body remains accepted for
	// non-LiveKit transitional clients; stage 5 LiveKit engine must send true.
	req := readMarkConnectedRequest(r)
	if h.Features.RTCLiveKit && !req.MediaPathConfirmed {
		httpx.WriteError(w, http.StatusConflict, "media path not confirmed")
		return
	}

	now := store.NowUTC()
	updated, err := h.Store.MarkCallConnected(call.ID, now)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	// Terminal race: store may return ended without mutation.
	if updated.Status == model.CallStatusEnded || updated.Status == model.CallStatusRejected || updated.Status == model.CallStatusMissed {
		h.applyCallPresence(updated, false)
		httpx.WriteError(w, http.StatusConflict, "call already ended")
		return
	}
	deviceID := strings.TrimSpace(r.Header.Get("X-Device-Id"))
	_ = h.Store.UpsertCallParticipant(model.CallParticipant{
		CallID:      call.ID,
		UserID:      claims.UserID,
		DeviceID:    deviceID,
		Role:        model.CallParticipantRoleJoined,
		InviteState: model.CallInviteStateJoined,
		MediaState:  model.CallMediaAudioOnly,
		JoinedAt:    &now,
	})

	updated = h.withCallParticipants(updated)
	h.applyCallPresence(updated, true)
	h.publishCallEvent(EventCallConnected, updated, claims.UserID)
	httpx.WriteJSON(w, http.StatusOK, model.CallActionResponse{Call: updated})
}

func (h *Handler) GetCallToken(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	if ok, reason := h.Features.AllowsLiveKitToken(); !ok {
		httpx.WriteError(w, http.StatusServiceUnavailable, reason)
		return
	}

	call, err := h.loadAuthorizedCall(r, claims.UserID)
	if err != nil {
		writeCallError(w, err)
		return
	}

	deviceID := strings.TrimSpace(r.Header.Get("X-Device-Id"))
	// Opaque identity for RTC edge (no username/phone; mapping stays in Go API).
	participantID := OpaqueParticipantID(h.Config.LiveKitSecret, call.ID, claims.UserID, deviceID)

	token, err := h.Rooms.IssueToken(call.LivekitRoomID, participantID)
	if err != nil {
		// Do not echo LiveKit config errors (may mention missing secrets).
		httpx.WriteError(w, http.StatusServiceUnavailable, "livekit unavailable")
		return
	}

	media := h.Rooms.MediaConfig(call.CallType, call.LowBandwidthMode)
	route := SelectRoute(h.Config, nil)
	httpx.WriteJSON(w, http.StatusOK, model.CallTokenResponse{
		Token:          token,
		LivekitURL:     h.Rooms.LiveKitURL(),
		RoomName:       call.LivekitRoomID,
		ParticipantID:  participantID,
		MediaConfig:    toModelMediaConfig(media),
		SelectedRegion: route.SelectedRegion,
		RouteClass:     route.RouteClass,
	})
}

func (h *Handler) GetICEServers(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	deviceID := strings.TrimSpace(r.Header.Get("X-Device-Id"))
	callID := strings.TrimSpace(r.URL.Query().Get("call_id"))
	// Short-lived credentials scoped by user/device/(optional call). Response
	// must not be logged (contains TURN credential material).
	httpx.WriteJSON(w, http.StatusOK, h.Rooms.ICEServersFor(ICECredentialScope{
		UserID:   claims.UserID,
		DeviceID: deviceID,
		CallID:   callID,
	}))
}

func (h *Handler) EnableLowBandwidth(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	call, err := h.loadAuthorizedCall(r, claims.UserID)
	if err != nil {
		writeCallError(w, err)
		return
	}
	if err := h.Store.SetLowBandwidthMode(call.ID, true); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	call.LowBandwidthMode = true
	h.publishCallEvent(EventCallLowBandwidth, call, claims.UserID)
	httpx.WriteJSON(w, http.StatusOK, model.CallActionResponse{Call: call})
}

func (h *Handler) loadAuthorizedCall(r *http.Request, userID string) (model.CallSession, error) {
	callID := strings.TrimSpace(r.PathValue("id"))
	if callID == "" {
		return model.CallSession{}, store.ErrNotFound
	}
	call, err := h.Store.GetCall(callID)
	if err != nil {
		return model.CallSession{}, err
	}
	// Prefer participant membership; keep legacy caller/callee fallback.
	if call.CallerID == userID || call.CalleeID == userID {
		return call, nil
	}
	ok, err := h.Store.IsCallParticipant(callID, userID)
	if err != nil {
		return model.CallSession{}, err
	}
	if ok {
		return call, nil
	}
	// Live group-chat room: any current member can inspect/join via the call link.
	if call.ChatID != "" && isLiveCallStatus(call.Status) &&
		NewGroupCallService(h.Store).RequireChatMember(call.ChatID, userID) == nil {
		return call, nil
	}
	return model.CallSession{}, store.ErrForbidden
}

func (h *Handler) GetActiveCallForChat(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	chatID := strings.TrimSpace(r.URL.Query().Get("chat_id"))
	if chatID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "chat_id is required")
		return
	}
	if !h.Features.GroupReadyAPI {
		httpx.WriteError(w, http.StatusServiceUnavailable, "group-ready calls are disabled")
		return
	}
	call, err := NewGroupCallService(h.Store).ActiveCallForChat(chatID, claims.UserID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			httpx.WriteJSON(w, http.StatusOK, map[string]any{"call": nil})
			return
		}
		writeCallError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"call": h.withCallParticipants(call)})
}

func (h *Handler) withCallParticipants(call model.CallSession) model.CallSession {
	parts, err := h.Store.ListCallParticipants(call.ID)
	if err != nil {
		return call
	}
	call.Participants = parts
	return call
}

func writeCallError(w http.ResponseWriter, err error) {
	status := http.StatusInternalServerError
	switch {
	case errors.Is(err, store.ErrNotFound):
		status = http.StatusNotFound
	case errors.Is(err, store.ErrForbidden):
		status = http.StatusForbidden
	case errors.Is(err, errCallNotJoinable), errors.Is(err, errChatNotGroup):
		status = http.StatusBadRequest
	case errors.Is(err, errCallEnded), errors.Is(err, errGroupParticipantLimit):
		status = http.StatusConflict
	}
	httpx.WriteError(w, status, err.Error())
}

func toModelMediaConfig(view CallMediaConfigView) model.CallMediaConfig {
	return model.CallMediaConfig{
		E2EE:             view.E2EE,
		AdaptiveStream:   view.AdaptiveStream,
		Dynacast:         view.Dynacast,
		Simulcast:        view.Simulcast,
		LowBandwidthAuto: view.LowBandwidthAuto,
		LowBandwidthMode: view.LowBandwidthMode,
		AudioFirst:       view.AudioFirst,
		PolicyVersion:    view.PolicyVersion,
		NetworkHint:      view.NetworkHint,
		Audio: model.AudioConfig{
			Codec:            view.Audio.Codec,
			Mono:             view.Audio.Mono,
			BitrateKbps:      view.Audio.BitrateKbps,
			Dtx:              view.Audio.Dtx,
			NoiseSuppression: view.Audio.NoiseSuppression,
			EchoCancellation: view.Audio.EchoCancellation,
			AutoGainControl:  view.Audio.AutoGainControl,
		},
		Video: model.VideoConfig{
			Enabled:   view.Video.Enabled,
			MaxWidth:  view.Video.MaxWidth,
			MaxHeight: view.Video.MaxHeight,
			MaxFps:    view.Video.MaxFps,
			Simulcast: view.Video.Simulcast,
		},
	}
}

func decodeCallKey(field, value string) ([]byte, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return nil, errors.New(field + " is required")
	}
	decoded, err := base64.StdEncoding.DecodeString(value)
	if err != nil {
		return nil, errors.New(field + " must be valid base64")
	}
	return decoded, nil
}
