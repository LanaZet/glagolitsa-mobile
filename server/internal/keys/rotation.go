// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package keys

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func (h *Handler) RotateSignedPreKey(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	deviceID := strings.TrimSpace(r.PathValue("id"))
	if deviceID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "device id is required")
		return
	}

	var req model.RotateSignedPreKeyRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}

	rotation, err := parseSignedPreKeyRotation(req)
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}

	if err := h.Store.RotateSignedPreKey(claims.UserID, deviceID, rotation); err != nil {
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

	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "rotated"})
}

func (h *Handler) CountRemainingPrekeys(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	deviceID := strings.TrimSpace(r.PathValue("id"))
	if deviceID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "device id is required")
		return
	}

	count, err := h.Store.CountRemainingPrekeys(claims.UserID, deviceID)
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

	httpx.WriteJSON(w, http.StatusOK, model.PrekeyCountResponse{
		DeviceID:         deviceID,
		RemainingPrekeys: count,
	})
}

func parseSignedPreKeyRotation(req model.RotateSignedPreKeyRequest) (store.SignedPreKeyRotation, error) {
	signedPublicKey, err := decodeBase64Field("signed_prekey.public_key", req.SignedPreKey.PublicKey)
	if err != nil {
		return store.SignedPreKeyRotation{}, err
	}
	signedSignature, err := decodeBase64Field("signed_prekey.signature", req.SignedPreKey.Signature)
	if err != nil {
		return store.SignedPreKeyRotation{}, err
	}
	if req.SignedPreKey.ID <= 0 {
		return store.SignedPreKeyRotation{}, errors.New("signed_prekey.id must be positive")
	}

	pqMaterial, err := decodeBase64Field("pq_prekey.public_material", req.PqPreKey.PublicMaterial)
	if err != nil {
		return store.SignedPreKeyRotation{}, err
	}
	pqSignature, err := decodeBase64Field("pq_prekey.signature", req.PqPreKey.Signature)
	if err != nil {
		return store.SignedPreKeyRotation{}, err
	}
	if req.PqPreKey.ID <= 0 {
		return store.SignedPreKeyRotation{}, errors.New("pq_prekey.id must be positive")
	}

	return store.SignedPreKeyRotation{
		SignedPreKey: store.SignedPreKeyRecord{
			ID:        req.SignedPreKey.ID,
			PublicKey: signedPublicKey,
			Signature: signedSignature,
			CreatedAt: req.SignedPreKey.CreatedAt,
		},
		PqPreKey: store.PqPreKeyRecord{
			ID:             req.PqPreKey.ID,
			PublicMaterial: pqMaterial,
			Signature:      pqSignature,
			CreatedAt:      req.PqPreKey.CreatedAt,
		},
	}, nil
}