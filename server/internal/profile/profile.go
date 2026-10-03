// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package profile

import (
	"encoding/json"
	"net/http"
	"strings"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
)

var allowedPresences = map[string]struct{}{
	"online":  {},
	"away":    {},
	"dnd":     {},
	"offline": {},
}

func (h *Handler) GetMyProfile(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	user, err := h.Store.GetProfile(claims.UserID)
	if err != nil {
		httpx.WriteError(w, http.StatusNotFound, "user not found")
		return
	}

	httpx.WriteJSON(w, http.StatusOK, user)
}

func (h *Handler) UpdateMyProfile(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req model.UpdateProfileRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}

	if req.DisplayName != nil {
		trimmed := strings.TrimSpace(*req.DisplayName)
		if len(trimmed) > 64 {
			httpx.WriteError(w, http.StatusBadRequest, "display_name is too long")
			return
		}
		req.DisplayName = &trimmed
	}
	if req.Status != nil {
		trimmed := strings.TrimSpace(*req.Status)
		if len(trimmed) > 120 {
			httpx.WriteError(w, http.StatusBadRequest, "status is too long")
			return
		}
		req.Status = &trimmed
	}
	if req.Bio != nil {
		trimmed := strings.TrimSpace(*req.Bio)
		if len(trimmed) > 500 {
			httpx.WriteError(w, http.StatusBadRequest, "bio is too long")
			return
		}
		req.Bio = &trimmed
	}
	if req.Nickname != nil {
		trimmed := strings.TrimSpace(*req.Nickname)
		if len(trimmed) > 32 {
			httpx.WriteError(w, http.StatusBadRequest, "nickname is too long")
			return
		}
		req.Nickname = &trimmed
	}
	if req.Position != nil {
		trimmed := strings.TrimSpace(*req.Position)
		if len(trimmed) > 64 {
			httpx.WriteError(w, http.StatusBadRequest, "position is too long")
			return
		}
		req.Position = &trimmed
	}
	if req.Presence != nil {
		normalized := strings.ToLower(strings.TrimSpace(*req.Presence))
		if _, ok := allowedPresences[normalized]; !ok {
			httpx.WriteError(w, http.StatusBadRequest, "invalid presence")
			return
		}
		req.Presence = &normalized
	}
	if req.AvatarURL != nil {
		trimmed := strings.TrimSpace(*req.AvatarURL)
		if trimmed != "" && !strings.HasPrefix(trimmed, "data:image/") {
			httpx.WriteError(w, http.StatusBadRequest, "avatar_url must be a data:image URI or empty")
			return
		}
		if len(trimmed) > 300000 {
			httpx.WriteError(w, http.StatusBadRequest, "avatar is too large")
			return
		}
		req.AvatarURL = &trimmed
	}

	user, err := h.Store.UpdateProfile(claims.UserID, req)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	httpx.WriteJSON(w, http.StatusOK, user)
}