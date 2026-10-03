// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package profile

import (
	"encoding/json"
	"net/http"

	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func (h *Handler) SearchProfiles(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	query := normalizeSearchQuery(r.URL.Query().Get("q"))
	if query == "" {
		writeSearchHits(w, nil)
		return
	}

	hits, err := h.Store.SearchProfiles(query, claims.UserID, searchLimit)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	h.recordSearchAudit(claims, "profile.search", query, len(hits))
	writeSearchHits(w, hits)
}

// AutocompleteProfiles — быстрый prefix-поиск для строки «Найти человека» (Mattermost autocomplete).
func (h *Handler) AutocompleteProfiles(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	query := normalizeSearchQuery(r.URL.Query().Get("q"))
	if len(query) < autocompleteMinLen {
		writeSearchHits(w, nil)
		return
	}

	hits, err := h.Store.AutocompleteProfiles(query, claims.UserID, autocompleteLimit)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	h.recordSearchAudit(claims, "profile.autocomplete", query, len(hits))
	writeSearchHits(w, hits)
}

// LookupProfile — exact match по username; пустой список при отсутствии (anti-enumeration).
func (h *Handler) LookupProfile(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	username := normalizeSearchQuery(r.URL.Query().Get("username"))
	if username == "" {
		username = normalizeSearchQuery(r.URL.Query().Get("u"))
	}
	if username == "" {
		writeSearchHits(w, nil)
		return
	}

	hit, err := h.Store.LookupProfileByUsername(username, claims.UserID)
	if err == store.ErrNotFound {
		h.recordSearchAudit(claims, "profile.lookup", username, 0)
		writeSearchHits(w, nil)
		return
	}
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	h.recordSearchAudit(claims, "profile.lookup", username, 1)
	writeSearchHits(w, []model.UserSearchHit{hit})
}

// GetUsersByIDs — Mattermost-style batch profile cards for avatar hydration in chat lists.
// Body: {"ids":["uuid",...]} → []UserSearchHit (id, username, display_name, avatar_url).
func (h *Handler) GetUsersByIDs(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req struct {
		IDs []string `json:"ids"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	hits, err := h.Store.GetProfilesByIDs(req.IDs, claims.UserID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeSearchHits(w, hits)
}

func writeSearchHits(w http.ResponseWriter, hits []model.UserSearchHit) {
	if hits == nil {
		hits = []model.UserSearchHit{}
	}
	httpx.WriteJSON(w, http.StatusOK, hits)
}

func (h *Handler) recordSearchAudit(claims auth.Claims, eventType, query string, resultCount int) {
	if h.Audit == nil {
		return
	}
	_ = h.Audit.RecordAudit(model.AuditEventInput{
		EventType: eventType,
		UserID:    claims.UserID,
		DeviceID:  claims.DeviceID,
		RiskLevel: "low",
		Metadata: map[string]any{
			"query_len":    len(query),
			"result_count": resultCount,
		},
	})
}