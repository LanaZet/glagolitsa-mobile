// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package linkpreview

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"

	"glagolitsa/server/internal/httpx"
)

type Handler struct {
	Service *Service
}

func NewHandler(service *Service) *Handler {
	return &Handler{Service: service}
}

type PreviewRequest struct {
	URL string `json:"url"`
}

func (h *Handler) FetchPreview(w http.ResponseWriter, r *http.Request) {
	if h.Service == nil {
		httpx.WriteError(w, http.StatusServiceUnavailable, "preview service unavailable")
		return
	}
	var req PreviewRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	req.URL = strings.TrimSpace(req.URL)
	if req.URL == "" {
		httpx.WriteError(w, http.StatusBadRequest, "url is required")
		return
	}
	preview, err := h.Service.Fetch(r.Context(), req.URL)
	if err != nil {
		switch {
		case errors.Is(err, ErrInvalidURL):
			httpx.WriteError(w, http.StatusBadRequest, err.Error())
		case errors.Is(err, ErrPrivateAddress), errors.Is(err, ErrRedirectBlocked):
			httpx.WriteError(w, http.StatusForbidden, err.Error())
		default:
			httpx.WriteError(w, http.StatusBadGateway, "failed to fetch preview")
		}
		return
	}
	httpx.WriteJSON(w, http.StatusOK, preview)
}
