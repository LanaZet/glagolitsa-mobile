// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/notification"
	"glagolitsa/server/internal/store"
)

// CreateCallInviteRequest invites a group-chat member into an active call.
type CreateCallInviteRequest struct {
	UserID string `json:"user_id"`
}

// CreateInvite POST /api/calls/{id}/invites
func (h *Handler) CreateInvite(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if !h.Features.GroupReadyAPI {
		httpx.WriteError(w, http.StatusServiceUnavailable, "group-ready calls are disabled")
		return
	}
	call, err := h.loadAuthorizedCall(r, claims.UserID)
	if err != nil {
		writeCallError(w, err)
		return
	}
	groupCalls := NewGroupCallService(h.Store)
	if !isGroupChatCall(call) {
		writeCallError(w, errCallNotJoinable)
		return
	}
	if isTerminalCallStatus(call.Status) {
		writeCallError(w, errCallEnded)
		return
	}

	var req CreateCallInviteRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	invitee := strings.TrimSpace(req.UserID)
	if invitee == "" {
		httpx.WriteError(w, http.StatusBadRequest, "user_id is required")
		return
	}
	if invitee == claims.UserID {
		httpx.WriteError(w, http.StatusBadRequest, "cannot invite yourself")
		return
	}

	if err := groupCalls.RequireChatMember(call.ChatID, invitee); err != nil {
		writeCallError(w, err)
		return
	}
	if err := groupCalls.EnsureCapacity(call.ID, invitee); err != nil {
		writeCallError(w, err)
		return
	}
	now := store.NowUTC()
	exp := now.Add(2 * time.Minute)
	inv, err := h.Store.CreateCallInvite(model.CallInvite{
		ID:              uuid.NewString(),
		CallID:          call.ID,
		InvitedUserID:   invitee,
		InvitedByUserID: claims.UserID,
		State:           model.CallInvitePending,
		CreatedAt:       now,
		ExpiresAt:       &exp,
	})
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if err := h.Store.UpsertCallParticipant(model.CallParticipant{
		CallID:      call.ID,
		UserID:      invitee,
		Role:        model.CallParticipantRoleInvited,
		InviteState: model.CallInviteStateRinging,
		MediaState:  model.CallMediaAudioOnly,
	}); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	_ = h.Notifier.NotifyIncomingCall(invitee, notification.IncomingCallPayload{
		CallID:   call.ID,
		CallerID: claims.UserID,
		CallType: call.CallType,
	})
	h.publishCallEvent(EventCallRinging, call, invitee)
	httpx.WriteJSON(w, http.StatusCreated, inv)
}

// AcceptInvite POST /api/calls/{id}/invites/accept — same room token path as accept.
func (h *Handler) AcceptInvite(w http.ResponseWriter, r *http.Request) {
	// Reuse AcceptCall: participant membership + same livekit room.
	h.AcceptCall(w, r)
}

// JoinCall POST /api/calls/{id}/join — any current group member may enter the live room.
func (h *Handler) JoinCall(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	callID := strings.TrimSpace(r.PathValue("id"))
	if callID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "call id is required")
		return
	}
	if !h.Features.GroupReadyAPI {
		httpx.WriteError(w, http.StatusServiceUnavailable, "group-ready calls are disabled")
		return
	}
	deviceID := strings.TrimSpace(r.Header.Get("X-Device-Id"))
	updated, err := NewGroupCallService(h.Store).Join(callID, claims.UserID, deviceID, store.NowUTC())
	if err != nil {
		writeCallError(w, err)
		return
	}
	updated = h.withCallParticipants(updated)
	h.publishCallEvent(EventCallAccepted, updated, claims.UserID)
	httpx.WriteJSON(w, http.StatusOK, model.CallActionResponse{Call: updated})
}
