// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"bytes"
	"sort"
	"time"

	"glagolitsa/server/internal/model"
)

type deviceRecord struct {
	AccountID              string
	DeviceStatus           string
	MailboxToken           string
	MailboxTokenPrevious   string
	MailboxPreviousExpires time.Time
	RegistrationID         int
	IdentityPublicKey      []byte
	SignedPreKey           SignedPreKeyRecord
	PqPreKey               PqPreKeyRecord
	Prekeys                map[int][]byte
	CreatedAt              time.Time
	LastSeenAt             time.Time
}

func (s *MemoryStore) RegisterDevice(registration DeviceRegistration) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.devices == nil {
		s.devices = make(map[string]*deviceRecord)
	}

	existing, ok := s.devices[registration.DeviceID]
	if ok && existing.AccountID != registration.AccountID {
		return 0, ErrForbidden
	}

	activeCount := 0
	for _, device := range s.devices {
		if device.AccountID != registration.AccountID {
			continue
		}
		// Do not count the device being re-registered as an existing active.
		if ok && device == existing {
			continue
		}
		status := device.DeviceStatus
		if status == "" {
			status = model.DeviceStatusActive
		}
		if status == model.DeviceStatusActive {
			activeCount++
		}
	}
	isUpdate := ok
	deviceStatus := model.DeviceStatusActive
	if activeCount > 0 && !isUpdate {
		deviceStatus = model.DeviceStatusPending
	}
	// Re-enroll non-active (incl. revoked) instead of leaving ghosts forever.
	if isUpdate && existing != nil && existing.DeviceStatus != model.DeviceStatusActive {
		if activeCount == 0 {
			deviceStatus = model.DeviceStatusActive
		} else {
			deviceStatus = model.DeviceStatusPending
		}
	}
	if registration.Attestation != nil && registration.Attestation.ConfirmingDeviceID != "" {
		if confirming, ok := s.devices[registration.Attestation.ConfirmingDeviceID]; ok &&
			confirming.AccountID == registration.AccountID &&
			confirming.DeviceStatus == model.DeviceStatusActive {
			deviceStatus = model.DeviceStatusActive
		}
	}

	mailbox := NewMailboxToken()
	if isUpdate && existing != nil && existing.MailboxToken != "" {
		mailbox = existing.MailboxToken
	}
	createdAt := NowUTC()
	if isUpdate && existing != nil && !existing.CreatedAt.IsZero() {
		createdAt = existing.CreatedAt
	}
	// Re-registration always replaces the OTP pool so public keys match the
	// install's private store. Preserving stale OTPs after reinstall is how
	// recipients hit InvalidKeyIdException: No such prekeyrecord.
	identityChanged := isUpdate && existing != nil &&
		!bytes.Equal(existing.IdentityPublicKey, registration.IdentityPublicKey)
	if isUpdate && identityChanged {
		// Permanently unreadable under the new identity/OTP set.
		if existing.MailboxToken != "" {
			delete(s.messageQueue, existing.MailboxToken)
		}
		if existing.MailboxTokenPrevious != "" {
			delete(s.messageQueue, existing.MailboxTokenPrevious)
		}
		delete(s.messageQueue, registration.DeviceID)
	}
	s.devices[registration.DeviceID] = &deviceRecord{
		AccountID:         registration.AccountID,
		DeviceStatus:      deviceStatus,
		MailboxToken:      mailbox,
		RegistrationID:    registration.RegistrationID,
		IdentityPublicKey: append([]byte(nil), registration.IdentityPublicKey...),
		SignedPreKey:      registration.SignedPreKey,
		PqPreKey:          registration.PqPreKey,
		// Fresh map every re-register (do not clone prior OTPs).
		Prekeys:    make(map[int][]byte),
		CreatedAt:  createdAt,
		LastSeenAt: NowUTC(),
	}

	stored := 0
	for _, prekey := range registration.OneTimePrekeys {
		if _, exists := s.devices[registration.DeviceID].Prekeys[prekey.ID]; exists {
			continue
		}
		s.devices[registration.DeviceID].Prekeys[prekey.ID] = append([]byte(nil), prekey.PublicKey...)
		stored++
	}

	if activeCount == 0 {
		if record, ok := s.users[registration.AccountID]; ok {
			record.AccountStatus = model.AccountStatusActive
		}
	}

	keyEventType := model.KeyEventRegistered
	if isUpdate {
		keyEventType = model.KeyEventIdentityUpdated
	}
	s.appendMemoryKeyChange(
		registration.AccountID,
		registration.DeviceID,
		keyEventType,
		registration.IdentityPublicKey,
		registration.SignedPreKey.ID,
	)
	return stored, nil
}

func (s *MemoryStore) ReplenishPrekeys(accountID, deviceID string, prekeys []OneTimePreKeyRecord) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	device, ok := s.devices[deviceID]
	if !ok {
		return 0, ErrNotFound
	}
	if device.AccountID != accountID {
		return 0, ErrForbidden
	}
	status := device.DeviceStatus
	if status == "" {
		status = model.DeviceStatusActive
	}
	if status != model.DeviceStatusActive {
		return 0, ErrForbidden
	}
	if device.Prekeys == nil {
		device.Prekeys = make(map[int][]byte)
	}

	stored := 0
	for _, prekey := range prekeys {
		if _, exists := device.Prekeys[prekey.ID]; exists {
			continue
		}
		device.Prekeys[prekey.ID] = append([]byte(nil), prekey.PublicKey...)
		stored++
	}
	return stored, nil
}

func (s *MemoryStore) GetDeviceBundle(deviceID string) (DeviceKeyBundleRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	device, ok := s.devices[deviceID]
	if !ok || len(device.IdentityPublicKey) == 0 {
		return DeviceKeyBundleRecord{}, ErrNotFound
	}
	status := device.DeviceStatus
	if status == "" {
		status = model.DeviceStatusActive
	}
	if status != model.DeviceStatusActive {
		return DeviceKeyBundleRecord{}, ErrForbidden
	}

	bundle := DeviceKeyBundleRecord{
		DeviceID:          deviceID,
		AccountID:         device.AccountID,
		RegistrationID:    device.RegistrationID,
		IdentityPublicKey: append([]byte(nil), device.IdentityPublicKey...),
		SignedPreKey:      device.SignedPreKey,
		PqPreKey:          device.PqPreKey,
	}

	if len(device.Prekeys) > 0 {
		ids := make([]int, 0, len(device.Prekeys))
		for id := range device.Prekeys {
			ids = append(ids, id)
		}
		sort.Ints(ids)
		prekeyID := ids[0]
		publicKey := device.Prekeys[prekeyID]
		delete(device.Prekeys, prekeyID)
		bundle.OneTimePreKey = &OneTimePreKeyRecord{
			ID:        prekeyID,
			PublicKey: append([]byte(nil), publicKey...),
		}
	}

	return bundle, nil
}

func (s *MemoryStore) DeviceOwnerAccountID(deviceID string) (string, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	device, ok := s.devices[deviceID]
	if !ok {
		return "", ErrNotFound
	}
	return device.AccountID, nil
}
