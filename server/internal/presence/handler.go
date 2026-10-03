// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package presence

import (
	"encoding/json"
	"net/http"
	"strings"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

type Handler struct {
	Svc *Service
}

func NewHandler(svc *Service) *Handler {
	return &Handler{Svc: svc}
}

func (h *Handler) Heartbeat(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req model.HeartbeatRequest
	_ = json.NewDecoder(r.Body).Decode(&req)
	deviceID := strings.TrimSpace(req.DeviceID)
	if deviceID == "" {
		deviceID = strings.TrimSpace(r.Header.Get("X-Device-Id"))
	}
	if err := h.Svc.Heartbeat(claims.UserID, deviceID); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *Handler) TypingStart(w http.ResponseWriter, r *http.Request) {
	h.typingAction(w, r, true)
}

func (h *Handler) TypingStop(w http.ResponseWriter, r *http.Request) {
	h.typingAction(w, r, false)
}

func (h *Handler) typingAction(w http.ResponseWriter, r *http.Request, start bool) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req model.TypingChatRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	chatID := strings.TrimSpace(req.ChatID)
	if chatID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "chat_id is required")
		return
	}
	var err error
	if start {
		err = h.Svc.TypingStart(claims.UserID, chatID)
	} else {
		err = h.Svc.TypingStop(claims.UserID, chatID)
	}
	if err != nil {
		writePresenceError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *Handler) RecordingStart(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req model.RecordingPresenceRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	if strings.TrimSpace(req.ChatID) == "" {
		httpx.WriteError(w, http.StatusBadRequest, "chat_id is required")
		return
	}
	if err := h.Svc.RecordingStart(claims.UserID, req.ChatID, req.Kind); err != nil {
		writePresenceError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *Handler) RecordingStop(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req model.RecordingPresenceRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	if strings.TrimSpace(req.ChatID) == "" {
		httpx.WriteError(w, http.StatusBadRequest, "chat_id is required")
		return
	}
	if err := h.Svc.RecordingStop(claims.UserID, req.ChatID); err != nil {
		writePresenceError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *Handler) ListUsers(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	raw := strings.TrimSpace(r.URL.Query().Get("ids"))
	if raw == "" {
		httpx.WriteError(w, http.StatusBadRequest, "ids is required")
		return
	}
	ids := strings.Split(raw, ",")
	users, err := h.Svc.ViewUsers(claims.UserID, ids)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusOK, model.UsersPresenceResponse{Users: users})
}

func (h *Handler) GetChatPresence(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	chatID := strings.TrimSpace(r.PathValue("id"))
	if chatID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "chat id is required")
		return
	}
	view, err := h.Svc.ViewChat(claims.UserID, chatID)
	if err != nil {
		writePresenceError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, view)
}

func (h *Handler) UpdatePrivacy(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req model.UpdatePresencePrivacyRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	settings, err := h.Svc.UpdatePrivacy(claims.UserID, req)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusOK, settings)
}

func (h *Handler) GetPrivacy(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	settings, err := h.Svc.GetPrivacy(claims.UserID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusOK, settings)
}

// Legacy handlers (совместимость с messaging routes).

func (h *Handler) LegacyGetUserPresence(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	userID := strings.TrimSpace(r.PathValue("id"))
	resp, err := h.Svc.LegacyPresenceResponse(claims.UserID, userID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) LegacySendTyping(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req model.TypingRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	if strings.TrimSpace(req.ChatID) == "" {
		httpx.WriteError(w, http.StatusBadRequest, "chat_id is required")
		return
	}
	var err error
	if req.Typing {
		err = h.Svc.TypingStart(claims.UserID, req.ChatID)
	} else {
		err = h.Svc.TypingStop(claims.UserID, req.ChatID)
	}
	if err != nil {
		writePresenceError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *Handler) LegacyUpdateMyPresence(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req model.UpdatePresenceRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	update := model.UpdatePresencePrivacyRequest{}
	if req.ShowLastSeen != nil {
		vis := model.PresenceVisibilityNobody
		if *req.ShowLastSeen {
			vis = model.PresenceVisibilityContacts
		}
		update.LastSeenVisibility = &vis
	}
	settings, err := h.Svc.UpdatePrivacy(claims.UserID, update)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	_ = h.Svc.Heartbeat(claims.UserID, "")
	resp, _ := h.Svc.LegacyPresenceResponse(claims.UserID, claims.UserID)
	resp.ShowLastSeen = settings.LastSeenVisibility != model.PresenceVisibilityNobody
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func writePresenceError(w http.ResponseWriter, err error) {
	if err == store.ErrForbidden || err == store.ErrNotFound {
		httpx.WriteError(w, http.StatusForbidden, err.Error())
		return
	}
	httpx.WriteError(w, http.StatusInternalServerError, err.Error())
}