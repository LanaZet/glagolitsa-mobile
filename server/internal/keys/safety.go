// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package keys

import (
	"errors"
	"net/http"
	"strings"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/store"
)

func (h *Handler) GetSafetyNumber(w http.ResponseWriter, r *http.Request) {
	_, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	deviceID := strings.TrimSpace(r.PathValue("id"))
	if deviceID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "device id is required")
		return
	}

	material, err := h.Store.GetSafetyNumberMaterial(deviceID)
	if err != nil {
		status := http.StatusInternalServerError
		switch {
		case errors.Is(err, store.ErrNotFound):
			status = http.StatusNotFound
		case errors.Is(err, store.ErrForbidden):
			status = http.StatusForbidden
		}
		httpx.WriteError(w, status, err.Error())
		return
	}

	httpx.WriteJSON(w, http.StatusOK, material)
}