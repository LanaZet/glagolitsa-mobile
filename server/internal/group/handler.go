// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package group

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/store"
)

type Handler struct {
	Service *Service
}

func NewHandler(service *Service) *Handler {
	return &Handler{Service: service}
}

func (h *Handler) CreateGroup(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req CreateGroupRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	resp, err := h.Service.CreateGroup(claims.UserID, req)
	if err != nil {
		h.writeError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusCreated, resp)
}

func (h *Handler) CreateChannel(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req CreateChannelRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	resp, err := h.Service.CreateChannel(claims.UserID, req)
	if err != nil {
		h.writeError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusCreated, resp)
}

func (h *Handler) CheckChannelSlug(w http.ResponseWriter, r *http.Request) {
	if _, ok := httpx.ClaimsFromContext(r); !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	slug := strings.TrimSpace(r.URL.Query().Get("slug"))
	ok, err := h.Service.CheckChannelSlug(slug)
	if err != nil {
		h.writeError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"slug": normalizeSlug(slug), "available": ok})
}

func (h *Handler) JoinChannelBySlug(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	slug := strings.TrimSpace(r.PathValue("slug"))
	resp, err := h.Service.JoinChannelBySlug(slug, claims.UserID)
	if err != nil {
		h.writeError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) SearchPublicChannels(w http.ResponseWriter, r *http.Request) {
	if _, ok := httpx.ClaimsFromContext(r); !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if q == "" {
		q = strings.TrimSpace(r.URL.Query().Get("query"))
	}
	resp, err := h.Service.SearchPublicChannels(q)
	if err != nil {
		h.writeError(w, err)
		return
	}
	if resp == nil {
		resp = []ChannelResponse{}
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) GetGroup(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	resp, err := h.Service.GetGroup(groupID, claims.UserID)
	if err != nil {
		h.writeError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) UpdateSettings(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	var req UpdateSettingsRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	resp, err := h.Service.UpdateSettings(groupID, claims.UserID, req)
	if err != nil {
		h.writeError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) AddMember(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	var req AddMemberRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	resp, err := h.Service.AddMember(groupID, claims.UserID, strings.TrimSpace(req.UserID))
	if err != nil {
		h.writeError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) RemoveMember(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	userID := strings.TrimSpace(r.PathValue("userId"))
	resp, err := h.Service.RemoveMember(groupID, claims.UserID, userID)
	if err != nil {
		h.writeError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) DeleteGroup(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	if err := h.Service.DeleteGroup(groupID, claims.UserID); err != nil {
		h.writeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) LeaveGroup(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	resp, err := h.Service.LeaveGroup(groupID, claims.UserID)
	if err != nil {
		h.writeError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) UpdateMemberRole(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	userID := strings.TrimSpace(r.PathValue("userId"))
	var req UpdateMemberRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	if err := h.Service.UpdateMemberRole(groupID, claims.UserID, userID, req.Role); err != nil {
		h.writeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) ListInvites(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	resp, err := h.Service.ListInvites(groupID, claims.UserID)
	if err != nil {
		h.writeError(w, err)
		return
	}
	if resp == nil {
		resp = []InviteResponse{}
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) RevokeInvite(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	inviteID := strings.TrimSpace(r.PathValue("inviteId"))
	if err := h.Service.RevokeInvite(groupID, claims.UserID, inviteID); err != nil {
		h.writeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) PreviewInvite(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	token := strings.TrimSpace(r.PathValue("token"))
	resp, err := h.Service.PreviewInvite(token, claims.UserID)
	if err != nil {
		h.writeError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) CreateInvite(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	var req CreateInviteRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	resp, err := h.Service.CreateInvite(groupID, claims.UserID, req)
	if err != nil {
		h.writeError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusCreated, resp)
}

func (h *Handler) JoinByInvite(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	token := strings.TrimSpace(r.PathValue("token"))
	resp, err := h.Service.JoinByInvite(token, claims.UserID)
	if err != nil {
		h.writeError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) RequestJoin(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	req, err := h.Service.RequestJoin(groupID, claims.UserID)
	if err != nil {
		h.writeError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusCreated, req)
}

func (h *Handler) ListJoinRequests(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	reqs, err := h.Service.ListJoinRequests(groupID, claims.UserID)
	if err != nil {
		h.writeError(w, err)
		return
	}
	if reqs == nil {
		reqs = []store.GroupJoinRequest{}
	}
	httpx.WriteJSON(w, http.StatusOK, reqs)
}

func (h *Handler) ApproveJoinRequest(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	requestID := strings.TrimSpace(r.PathValue("requestId"))
	resp, err := h.Service.ApproveJoinRequest(groupID, claims.UserID, requestID)
	if err != nil {
		h.writeError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) RejectJoinRequest(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	requestID := strings.TrimSpace(r.PathValue("requestId"))
	if err := h.Service.RejectJoinRequest(groupID, claims.UserID, requestID); err != nil {
		h.writeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) MuteMember(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	userID := strings.TrimSpace(r.PathValue("userId"))
	var req MuteMemberRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	if err := h.Service.MuteMember(groupID, claims.UserID, userID, req.DurationMinutes); err != nil {
		h.writeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) UnmuteMember(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	userID := strings.TrimSpace(r.PathValue("userId"))
	if err := h.Service.UnmuteMember(groupID, claims.UserID, userID); err != nil {
		h.writeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) BanMember(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	userID := strings.TrimSpace(r.PathValue("userId"))
	var req BanMemberRequest
	_ = json.NewDecoder(r.Body).Decode(&req)
	resp, err := h.Service.BanMember(groupID, claims.UserID, userID, req.Reason)
	if err != nil {
		h.writeError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) UnbanMember(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	userID := strings.TrimSpace(r.PathValue("userId"))
	if err := h.Service.UnbanMember(groupID, claims.UserID, userID); err != nil {
		h.writeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) PinItem(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	var req PinItemRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	item, err := h.Service.PinItem(groupID, claims.UserID, req.ItemRef)
	if err != nil {
		h.writeError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusCreated, item)
}

func (h *Handler) UnpinItem(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	itemID := strings.TrimSpace(r.PathValue("itemId"))
	if err := h.Service.UnpinItem(groupID, claims.UserID, itemID); err != nil {
		h.writeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) ListPinned(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	items, err := h.Service.ListPinned(groupID, claims.UserID)
	if err != nil {
		h.writeError(w, err)
		return
	}
	if items == nil {
		items = []store.GroupPinnedItem{}
	}
	httpx.WriteJSON(w, http.StatusOK, items)
}

func (h *Handler) ListAudit(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	events, err := h.Service.ListAudit(groupID, claims.UserID, limit)
	if err != nil {
		h.writeError(w, err)
		return
	}
	if events == nil {
		events = []store.GroupAuditEvent{}
	}
	httpx.WriteJSON(w, http.StatusOK, events)
}

func (h *Handler) KeyEpoch(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.PathValue("id"))
	resp, err := h.Service.KeyEpoch(groupID, claims.UserID)
	if err != nil {
		h.writeError(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (h *Handler) writeError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, ErrNotFound):
		httpx.WriteError(w, http.StatusNotFound, err.Error())
	case errors.Is(err, ErrForbidden):
		httpx.WriteError(w, http.StatusForbidden, err.Error())
	case errors.Is(err, ErrAlreadyExists):
		httpx.WriteError(w, http.StatusConflict, err.Error())
	case errors.Is(err, ErrGone):
		httpx.WriteError(w, http.StatusGone, err.Error())
	default:
		if strings.Contains(err.Error(), "rate limit") {
			httpx.WriteError(w, http.StatusTooManyRequests, err.Error())
			return
		}
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
	}
}
