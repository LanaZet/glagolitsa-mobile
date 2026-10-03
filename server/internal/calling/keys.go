// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"encoding/json"
	"net/http"
	"strings"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func (h *Handler) SubmitCallKeys(w http.ResponseWriter, r *http.Request) {
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

	var req model.SubmitCallKeysRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	if len(req.Offers) == 0 {
		httpx.WriteError(w, http.StatusBadRequest, "offers is required")
		return
	}

	sourceDeviceID := strings.TrimSpace(r.Header.Get("X-Device-Id"))
	// Only joined/accepted devices may receive media key offers.
	parts, err := h.Store.ListCallParticipants(call.ID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	filtered := FilterKeyOfferTargets(req.Offers, parts)
	if len(filtered) == 0 {
		httpx.WriteError(w, http.StatusBadRequest, "no eligible key offer targets")
		return
	}

	offers := make([]store.CallKeyOfferRecord, 0, len(filtered))
	for _, offer := range filtered {
		key, err := decodeCallKey("encrypted_key", offer.EncryptedKey)
		if err != nil {
			httpx.WriteError(w, http.StatusBadRequest, err.Error())
			return
		}
		if offer.EnvelopeType <= 0 {
			httpx.WriteError(w, http.StatusBadRequest, "envelope_type is required")
			return
		}
		// Never log encrypted_key material.
		offers = append(offers, store.CallKeyOfferRecord{
			SourceUserID:   claims.UserID,
			SourceDeviceID: sourceDeviceID,
			TargetUserID:   strings.TrimSpace(offer.TargetUserID),
			TargetDeviceID: strings.TrimSpace(offer.TargetDeviceID),
			EnvelopeType:   offer.EnvelopeType,
			EncryptedKey:   key,
		})
	}

	if err := h.Store.StoreCallKeyOffers(call.ID, offers); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *Handler) ListCallKeys(w http.ResponseWriter, r *http.Request) {
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

	deviceID := strings.TrimSpace(r.Header.Get("X-Device-Id"))
	if deviceID == "" {
		deviceID = strings.TrimSpace(r.URL.Query().Get("device_id"))
	}
	if deviceID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "X-Device-Id is required")
		return
	}

	offers, err := h.Store.ListCallKeyOffersForDevice(call.ID, claims.UserID, deviceID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if offers == nil {
		offers = []model.CallKeyOffer{}
	}
	httpx.WriteJSON(w, http.StatusOK, offers)
}
