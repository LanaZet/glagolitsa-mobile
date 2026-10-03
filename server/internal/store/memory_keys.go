// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"encoding/base64"

	"github.com/google/uuid"

	"glagolitsa/server/internal/model"
)

func (s *MemoryStore) RotateSignedPreKey(accountID, deviceID string, rotation SignedPreKeyRotation) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	device, ok := s.devices[deviceID]
	if !ok || device.AccountID != accountID {
		return ErrNotFound
	}
	status := device.DeviceStatus
	if status == "" {
		status = model.DeviceStatusActive
	}
	if status != model.DeviceStatusActive {
		return ErrForbidden
	}

	device.SignedPreKey = rotation.SignedPreKey
	device.PqPreKey = rotation.PqPreKey
	s.appendMemoryKeyChange(accountID, deviceID, model.KeyEventSignedPrekeyRot, device.IdentityPublicKey, rotation.SignedPreKey.ID)
	return nil
}

func (s *MemoryStore) ListKeyChangeEvents(accountID string, limit int) ([]KeyChangeEventRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if s.keyChangeEvents == nil {
		return []KeyChangeEventRecord{}, nil
	}
	events := s.keyChangeEvents[accountID]
	if limit > 0 && len(events) > limit {
		events = events[:limit]
	}
	out := make([]KeyChangeEventRecord, len(events))
	copy(out, events)
	return out, nil
}

func (s *MemoryStore) GetSafetyNumberMaterial(deviceID string) (model.SafetyNumberResponse, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	device, ok := s.devices[deviceID]
	if !ok || len(device.IdentityPublicKey) == 0 {
		return model.SafetyNumberResponse{}, ErrNotFound
	}
	status := device.DeviceStatus
	if status == "" {
		status = model.DeviceStatusActive
	}
	if status != model.DeviceStatusActive {
		return model.SafetyNumberResponse{}, ErrForbidden
	}
	return model.SafetyNumberResponse{
		DeviceID:          deviceID,
		AccountID:         device.AccountID,
		RegistrationID:    device.RegistrationID,
		IdentityPublicKey: base64.StdEncoding.EncodeToString(device.IdentityPublicKey),
	}, nil
}

func (s *MemoryStore) CountRemainingPrekeys(accountID, deviceID string) (int, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	device, ok := s.devices[deviceID]
	if !ok || device.AccountID != accountID {
		return 0, ErrNotFound
	}
	if device.Prekeys == nil {
		return 0, nil
	}
	return len(device.Prekeys), nil
}

func (s *MemoryStore) PurgeDeviceKeys(deviceID, accountID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	device, ok := s.devices[deviceID]
	if !ok || device.AccountID != accountID {
		return ErrNotFound
	}
	identityKey := append([]byte(nil), device.IdentityPublicKey...)
	device.IdentityPublicKey = nil
	device.SignedPreKey = SignedPreKeyRecord{}
	device.PqPreKey = PqPreKeyRecord{}
	device.Prekeys = nil
	device.DeviceStatus = model.DeviceStatusRevoked
	s.appendMemoryKeyChange(accountID, deviceID, model.KeyEventDeviceRevoked, identityKey, 0)
	return nil
}

func (s *MemoryStore) appendMemoryKeyChange(accountID, deviceID, eventType string, identityPublicKey []byte, signedPrekeyID int) {
	if s.keyChangeEvents == nil {
		s.keyChangeEvents = make(map[string][]KeyChangeEventRecord)
	}
	events := s.keyChangeEvents[accountID]
	prevHex := ""
	if len(events) > 0 {
		prevHex = HashHex(events[len(events)-1].EventHash)
	}
	idHash := IdentityKeyHash(identityPublicKey)
	eventHash := KeyChangeEventHash(prevHex, accountID, deviceID, eventType, idHash, signedPrekeyID)
	var prevHash []byte
	if prevHex != "" {
		prevHash = events[len(events)-1].EventHash
	}
	events = append(events, KeyChangeEventRecord{
		ID:              uuid.NewString(),
		AccountID:       accountID,
		DeviceID:        deviceID,
		EventType:       eventType,
		IdentityKeyHash: idHash,
		SignedPrekeyID:  signedPrekeyID,
		PrevEventHash:   prevHash,
		EventHash:       eventHash,
		CreatedAt:       NowUTC(),
	})
	s.keyChangeEvents[accountID] = events
}
