// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"glagolitsa/server/internal/model"
)

func (s *MemoryStore) TouchDeviceLastSeen(deviceID, accountID string) error {
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
	if status != model.DeviceStatusActive && status != model.DeviceStatusPending {
		return ErrNotFound
	}
	device.LastSeenAt = NowUTC()
	return nil
}

func (s *MemoryStore) PurgeDeviceMailbox(deviceID, accountID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	device, ok := s.devices[deviceID]
	if !ok || device.AccountID != accountID {
		return ErrNotFound
	}
	tokens := []string{deviceID}
	if device.MailboxToken != "" {
		tokens = append(tokens, device.MailboxToken)
	}
	if device.MailboxTokenPrevious != "" {
		tokens = append(tokens, device.MailboxTokenPrevious)
	}
	for _, token := range tokens {
		delete(s.messageQueue, token)
	}
	return nil
}

func (s *MemoryStore) PurgeStaleDevices() (int, error) {
	now := NowUTC()
	var candidates []stalePurgeCandidate

	s.mu.RLock()
	for id, device := range s.devices {
		status := device.DeviceStatus
		if status == "" {
			status = model.DeviceStatusActive
		}
		if status != model.DeviceStatusActive && status != model.DeviceStatusPending {
			continue
		}
		seen := device.LastSeenAt
		if seen.IsZero() {
			seen = device.CreatedAt
		}
		if seen.IsZero() {
			seen = now
		}
		candidates = append(candidates, stalePurgeCandidate{
			DeviceID:       id,
			AccountID:      device.AccountID,
			Status:         status,
			SeenAt:         seen,
			HasIdentityKey: len(device.IdentityPublicKey) > 0,
		})
	}
	s.mu.RUnlock()

	purged := 0
	for _, t := range filterStalePurgeTargets(candidates, now) {
		if err := s.RevokeDevice(t.DeviceID, t.AccountID); err != nil {
			if err == ErrNotFound {
				continue
			}
			return purged, err
		}
		purged++
	}
	return purged, nil
}
