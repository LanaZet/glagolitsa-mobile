// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"strings"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

// LiveKitWebhook handles a minimal subset of LiveKit webhooks for reconciliation.
// Auth: Authorization header must match LIVEKIT_WEBHOOK_API_KEY or HMAC of body
// with LIVEKIT_API_SECRET when configured. Never logs body (may contain identities).
func (h *Handler) LiveKitWebhook(w http.ResponseWriter, r *http.Request) {
	if !h.Features.RTCLiveKit {
		httpx.WriteError(w, http.StatusServiceUnavailable, "livekit webhooks disabled")
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid body")
		return
	}
	if !h.verifyWebhook(r, body) {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var payload struct {
		Event string `json:"event"`
		Room  struct {
			Name string `json:"name"`
		} `json:"room"`
		Participant struct {
			Identity string `json:"identity"`
		} `json:"participant"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}

	callID := roomNameToCallID(payload.Room.Name)
	if callID == "" {
		// Unknown room shape — ack to avoid retry storms; no leak.
		httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ignored"})
		return
	}
	call, err := h.Store.GetCall(callID)
	if err != nil {
		httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "unknown_call"})
		return
	}

	now := store.NowUTC()
	switch strings.ToLower(payload.Event) {
	case "participant_joined", "participant_join":
		h.publishCallEvent(EventCallParticipantJoined, call, "")
	case "participant_left", "participant_leave":
		h.publishCallEvent(EventCallParticipantLeft, call, "")
	case "room_finished", "room_ended":
		if call.Status != model.CallStatusEnded && call.Status != model.CallStatusRejected && call.Status != model.CallStatusMissed {
			updated, err := h.Store.EndCall(call.ID, now)
			if err == nil {
				h.applyCallPresence(updated, false)
				h.publishCallEvent(EventCallEnded, updated, "")
			}
		}
	default:
		// track_published / unpublished etc. — ignore for now
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *Handler) verifyWebhook(r *http.Request, body []byte) bool {
	// Shared static header for self-hosted smoke (optional).
	if key := strings.TrimSpace(h.Config.LiveKitAPIKey); key != "" {
		if r.Header.Get("Authorization") == "Bearer "+key {
			return true
		}
	}
	// HMAC-SHA256 hex of body with API secret (simple self-hosted check).
	secret := strings.TrimSpace(h.Config.LiveKitSecret)
	if secret == "" {
		return false
	}
	sig := strings.TrimSpace(r.Header.Get("X-Livekit-Signature"))
	if sig == "" {
		sig = strings.TrimSpace(r.Header.Get("X-Webhook-Signature"))
	}
	if sig == "" {
		return false
	}
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write(body)
	expected := hex.EncodeToString(mac.Sum(nil))
	return hmac.Equal([]byte(strings.ToLower(sig)), []byte(strings.ToLower(expected)))
}

func roomNameToCallID(room string) string {
	room = strings.TrimSpace(room)
	const prefix = "call-"
	if !strings.HasPrefix(room, prefix) {
		return ""
	}
	id := strings.TrimPrefix(room, prefix)
	// Basic UUID shape guard (no injection into SQL — store uses parameterized queries).
	if len(id) < 8 || len(id) > 64 {
		return ""
	}
	return id
}

// MarkConnectedRequest optional body for media-path confirmation.
type MarkConnectedRequest struct {
	MediaPathConfirmed bool `json:"media_path_confirmed"`
}

// Parse optional JSON body for MarkConnected without failing on empty body.
func readMarkConnectedRequest(r *http.Request) MarkConnectedRequest {
	if r.Body == nil {
		return MarkConnectedRequest{}
	}
	var req MarkConnectedRequest
	dec := json.NewDecoder(io.LimitReader(r.Body, 4096))
	_ = dec.Decode(&req)
	return req
}

