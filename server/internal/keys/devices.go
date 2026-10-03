// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package keys

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/relayauth"
	"glagolitsa/server/internal/store"
)

func (h *Handler) RegisterDevice(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req model.RegisterDeviceRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}

	registration, err := parseDeviceRegistration(claims.UserID, req)
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}

	stored, err := h.Store.RegisterDevice(registration)
	if err != nil {
		status := http.StatusInternalServerError
		if errors.Is(err, store.ErrForbidden) {
			status = http.StatusForbidden
		}
		httpx.WriteError(w, status, err.Error())
		return
	}

	ids := make([]int, 0, len(req.OneTimePrekeys))
	for _, prekey := range req.OneTimePrekeys {
		ids = append(ids, prekey.ID)
	}

	httpx.WriteJSON(w, http.StatusCreated, model.RegisterDeviceResponse{
		DeviceID:         req.DeviceID,
		PrekeysStored:    stored,
		OneTimePrekeyIDs: ids,
	})

	recordDeviceIntegritySignal(h.Store, claims.UserID, req.DeviceID, req.Attestation)
}

func (h *Handler) GetDeviceBundle(w http.ResponseWriter, r *http.Request) {
	deviceID := strings.TrimSpace(r.PathValue("id"))
	if deviceID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "device id is required")
		return
	}

	bundle, err := h.Store.GetDeviceBundle(deviceID)
	if err != nil {
		status := http.StatusInternalServerError
		if errors.Is(err, store.ErrNotFound) {
			status = http.StatusNotFound
		}
		httpx.WriteError(w, status, err.Error())
		return
	}

	if accountID, ownerErr := h.Store.DeviceOwnerAccountID(deviceID); ownerErr == nil {
		if remaining, countErr := h.Store.CountRemainingPrekeys(accountID, deviceID); countErr == nil {
			if remaining < prekeyLowThreshold && h.Notifier != nil {
				_ = h.Notifier.NotifyPrekeysLow(accountID, deviceID, remaining)
			}
		}
	}

	httpx.WriteJSON(w, http.StatusOK, deviceBundleToModel(bundle))
}

func (h *Handler) RotateMailbox(w http.ResponseWriter, r *http.Request) {
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

	headerDeviceID := strings.TrimSpace(r.Header.Get("X-Device-Id"))
	if headerDeviceID != "" && headerDeviceID != deviceID {
		httpx.WriteError(w, http.StatusBadRequest, "X-Device-Id must match device id")
		return
	}

	response, err := h.Store.RotateDeviceMailbox(deviceID, claims.UserID)
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

	httpx.WriteJSON(w, http.StatusOK, response)
}

func (h *Handler) ReplenishPrekeys(w http.ResponseWriter, r *http.Request) {
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

	var req model.ReplenishPrekeysRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid json")
		return
	}
	if len(req.OneTimePrekeys) == 0 {
		httpx.WriteError(w, http.StatusBadRequest, "one_time_prekeys is required")
		return
	}

	prekeys := make([]store.OneTimePreKeyRecord, 0, len(req.OneTimePrekeys))
	for _, prekey := range req.OneTimePrekeys {
		publicKey, err := decodeBase64Field("one_time_prekeys.public_key", prekey.PublicKey)
		if err != nil {
			httpx.WriteError(w, http.StatusBadRequest, err.Error())
			return
		}
		if prekey.ID <= 0 {
			httpx.WriteError(w, http.StatusBadRequest, "one_time_prekeys.id must be positive")
			return
		}
		prekeys = append(prekeys, store.OneTimePreKeyRecord{
			ID:        prekey.ID,
			PublicKey: publicKey,
		})
	}

	stored, err := h.Store.ReplenishPrekeys(claims.UserID, deviceID, prekeys)
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

	httpx.WriteJSON(w, http.StatusOK, model.ReplenishPrekeysResponse{
		DeviceID:      deviceID,
		PrekeysStored: stored,
	})
}

func (h *Handler) ListUserDevices(w http.ResponseWriter, r *http.Request) {
	claims, ok := httpx.ClaimsFromContext(r)
	if !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	accountID := strings.TrimSpace(r.PathValue("id"))
	if accountID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "user id is required")
		return
	}

	activeOnly := claims.UserID != accountID
	devices, err := h.Store.ListUserDevices(accountID, activeOnly)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if devices == nil {
		devices = []model.UserDevice{}
	}
	for i := range devices {
		devices[i].DeliveryToken = relayauth.DeliveryTokenForMailbox(devices[i].MailboxToken)
	}
	httpx.WriteJSON(w, http.StatusOK, devices)
}

func parseDeviceRegistration(accountID string, req model.RegisterDeviceRequest) (store.DeviceRegistration, error) {
	if strings.TrimSpace(req.DeviceID) == "" {
		return store.DeviceRegistration{}, errors.New("device_id is required")
	}
	if req.RegistrationID <= 0 {
		return store.DeviceRegistration{}, errors.New("registration_id must be positive")
	}

	identityKey, err := decodeBase64Field("identity_public_key", req.IdentityPublicKey)
	if err != nil {
		return store.DeviceRegistration{}, err
	}
	if len(identityKey) == 0 {
		return store.DeviceRegistration{}, errors.New("identity_public_key is required")
	}

	signedPublicKey, err := decodeBase64Field("signed_prekey.public_key", req.SignedPreKey.PublicKey)
	if err != nil {
		return store.DeviceRegistration{}, err
	}
	signedSignature, err := decodeBase64Field("signed_prekey.signature", req.SignedPreKey.Signature)
	if err != nil {
		return store.DeviceRegistration{}, err
	}
	if req.SignedPreKey.ID <= 0 {
		return store.DeviceRegistration{}, errors.New("signed_prekey.id must be positive")
	}
	if req.SignedPreKey.CreatedAt <= 0 {
		return store.DeviceRegistration{}, errors.New("signed_prekey.created_at must be positive")
	}

	pqMaterial, err := decodeBase64Field("pq_prekey.public_material", req.PqPreKey.PublicMaterial)
	if err != nil {
		return store.DeviceRegistration{}, err
	}
	pqSignature, err := decodeBase64Field("pq_prekey.signature", req.PqPreKey.Signature)
	if err != nil {
		return store.DeviceRegistration{}, err
	}
	if req.PqPreKey.ID <= 0 {
		return store.DeviceRegistration{}, errors.New("pq_prekey.id must be positive")
	}
	if req.PqPreKey.CreatedAt <= 0 {
		return store.DeviceRegistration{}, errors.New("pq_prekey.created_at must be positive")
	}

	if len(req.OneTimePrekeys) == 0 {
		return store.DeviceRegistration{}, errors.New("one_time_prekeys is required")
	}

	oneTimePrekeys := make([]store.OneTimePreKeyRecord, 0, len(req.OneTimePrekeys))
	for _, prekey := range req.OneTimePrekeys {
		publicKey, err := decodeBase64Field("one_time_prekeys.public_key", prekey.PublicKey)
		if err != nil {
			return store.DeviceRegistration{}, err
		}
		if prekey.ID <= 0 {
			return store.DeviceRegistration{}, errors.New("one_time_prekeys.id must be positive")
		}
		oneTimePrekeys = append(oneTimePrekeys, store.OneTimePreKeyRecord{
			ID:        prekey.ID,
			PublicKey: publicKey,
		})
	}

	registration := store.DeviceRegistration{
		DeviceID:          strings.TrimSpace(req.DeviceID),
		AccountID:         accountID,
		RegistrationID:    req.RegistrationID,
		IdentityPublicKey: identityKey,
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
		OneTimePrekeys: oneTimePrekeys,
	}
	if req.Attestation != nil && strings.TrimSpace(req.Attestation.Signature) != "" {
		signature, err := decodeBase64Field("attestation.signature", req.Attestation.Signature)
		if err != nil {
			return store.DeviceRegistration{}, err
		}
		registration.Attestation = &store.DeviceAttestationRecord{
			ConfirmingDeviceID: strings.TrimSpace(req.Attestation.ConfirmingDeviceID),
			Signature:          signature,
		}
	}
	return registration, nil
}

func recordDeviceIntegritySignal(store Store, accountID, deviceID string, attestation *model.DeviceAttestation) {
	if attestation == nil {
		return
	}
	provider := strings.TrimSpace(attestation.Provider)
	tokenPresent := strings.TrimSpace(attestation.Token) != ""
	if provider == "" && !tokenPresent {
		return
	}
	if err := store.RecordAudit(model.AuditEventInput{
		EventType: "device_integrity_signal",
		UserID:    accountID,
		DeviceID:  deviceID,
		RiskLevel: "info",
		Metadata: map[string]any{
			"provider":      provider,
			"token_present": tokenPresent,
		},
	}); err != nil {
		slog.Warn("device_integrity_audit_failed", "user_id", accountID, "device_id", deviceID, "error", err.Error())
	}
}

func deviceBundleToModel(bundle store.DeviceKeyBundleRecord) model.DeviceKeyBundle {
	response := model.DeviceKeyBundle{
		DeviceID:          bundle.DeviceID,
		AccountID:         bundle.AccountID,
		RegistrationID:    bundle.RegistrationID,
		IdentityPublicKey: encodeBase64(bundle.IdentityPublicKey),
		SignedPreKey: model.SignedPreKeyMaterial{
			ID:        bundle.SignedPreKey.ID,
			PublicKey: encodeBase64(bundle.SignedPreKey.PublicKey),
			Signature: encodeBase64(bundle.SignedPreKey.Signature),
			CreatedAt: bundle.SignedPreKey.CreatedAt,
		},
		PqPreKey: model.PqPreKeyMaterial{
			ID:             bundle.PqPreKey.ID,
			PublicMaterial: encodeBase64(bundle.PqPreKey.PublicMaterial),
			Signature:      encodeBase64(bundle.PqPreKey.Signature),
			CreatedAt:      bundle.PqPreKey.CreatedAt,
		},
	}
	if bundle.OneTimePreKey != nil {
		response.OneTimePreKey = &model.OneTimePreKeyMaterial{
			ID:        bundle.OneTimePreKey.ID,
			PublicKey: encodeBase64(bundle.OneTimePreKey.PublicKey),
		}
	}
	return response
}

func decodeBase64Field(field, value string) ([]byte, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return nil, errors.New(field + " is required")
	}
	decoded, err := base64.StdEncoding.DecodeString(value)
	if err != nil {
		return nil, errors.New(field + " must be valid base64")
	}
	return decoded, nil
}

func encodeBase64(value []byte) string {
	return base64.StdEncoding.EncodeToString(value)
}
