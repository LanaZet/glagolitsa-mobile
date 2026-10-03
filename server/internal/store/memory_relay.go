// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"encoding/base64"
	"sort"
	"time"

	"github.com/google/uuid"

	"glagolitsa/server/internal/model"
)

type queuedEnvelopeRecord struct {
	QueuedEnvelopeRecord
}

func (s *MemoryStore) ListUserDevices(accountID string, activeOnly bool) ([]model.UserDevice, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	devices := make([]model.UserDevice, 0)
	for deviceID, device := range s.devices {
		if device.AccountID != accountID {
			continue
		}
		status := device.DeviceStatus
		if status == "" {
			status = model.DeviceStatusActive
		}
		if activeOnly && (status != model.DeviceStatusActive || len(device.IdentityPublicKey) == 0) {
			continue
		}
		mailboxToken := device.MailboxToken
		if mailboxToken == "" {
			mailboxToken = deviceID
		}
		devices = append(devices, model.UserDevice{
			DeviceID:          deviceID,
			MailboxToken:      mailboxToken,
			RegistrationID:    device.RegistrationID,
			IdentityPublicKey: base64.StdEncoding.EncodeToString(device.IdentityPublicKey),
			DeviceStatus:      status,
		})
	}
	sort.Slice(devices, func(i, j int) bool { return devices[i].DeviceID < devices[j].DeviceID })
	return devices, nil
}

func (s *MemoryStore) MailboxOwnerAccountID(mailboxToken string) (string, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	now := NowUTC()
	for deviceID, device := range s.devices {
		token := device.MailboxToken
		if token == "" {
			token = deviceID
		}
		if token == mailboxToken || deviceID == mailboxToken {
			return device.AccountID, nil
		}
		if device.MailboxTokenPrevious != "" &&
			device.MailboxTokenPrevious == mailboxToken &&
			device.MailboxPreviousExpires.After(now) {
			return device.AccountID, nil
		}
	}
	return "", ErrNotFound
}

func (s *MemoryStore) GetDeviceMailboxToken(deviceID, accountID string) (string, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	device, ok := s.devices[deviceID]
	if !ok || device.AccountID != accountID {
		return "", ErrNotFound
	}
	if device.MailboxToken != "" {
		return device.MailboxToken, nil
	}
	return deviceID, nil
}

func (s *MemoryStore) ResolveMailboxTokens(deviceID, accountID string) ([]string, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	device, ok := s.devices[deviceID]
	if !ok || device.AccountID != accountID {
		return nil, ErrNotFound
	}
	current := device.MailboxToken
	if current == "" {
		current = deviceID
	}
	tokens := []string{current}
	if device.MailboxTokenPrevious != "" && device.MailboxPreviousExpires.After(NowUTC()) {
		tokens = append(tokens, device.MailboxTokenPrevious)
	}
	return tokens, nil
}

func (s *MemoryStore) RotateDeviceMailbox(deviceID, accountID string) (model.RotateMailboxResponse, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	device, ok := s.devices[deviceID]
	if !ok || device.AccountID != accountID {
		return model.RotateMailboxResponse{}, ErrNotFound
	}
	current := device.MailboxToken
	if current == "" {
		current = deviceID
	}
	newToken := NewMailboxToken()
	graceUntil := NowUTC().Add(24 * time.Hour)
	device.MailboxTokenPrevious = current
	device.MailboxPreviousExpires = graceUntil
	device.MailboxToken = newToken
	return model.RotateMailboxResponse{
		DeviceID:             deviceID,
		MailboxToken:         newToken,
		PreviousMailboxToken: current,
		PreviousExpiresAt:    graceUntil.Format(time.RFC3339),
	}, nil
}

func (s *MemoryStore) EnqueueRelayEnvelopes(envelopes []RelayEnvelopeInput) ([]string, error) {
	if s.relayQueue != nil {
		return s.relayQueue.EnqueueRelayEnvelopes(envelopes)
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.messageQueue == nil {
		s.messageQueue = make(map[string][]queuedEnvelopeRecord)
	}

	ids := make([]string, 0, len(envelopes))
	for _, envelope := range envelopes {
		if _, err := s.mailboxOwnerAccountIDLocked(envelope.MailboxToken); err != nil {
			return nil, ErrNotFound
		}
		envelopeID := uuid.NewString()
		expiresAt := envelope.ExpiresAt
		if expiresAt.IsZero() {
			expiresAt = NowUTC().Add(30 * 24 * time.Hour)
		}
		record := queuedEnvelopeRecord{
			QueuedEnvelopeRecord: QueuedEnvelopeRecord{
				EnvelopeID:   envelopeID,
				MailboxToken: envelope.MailboxToken,
				EnvelopeType: envelope.EnvelopeType,
				Ciphertext:   append([]byte(nil), envelope.Ciphertext...),
				SizeBucket:   envelope.SizeBucket,
				CreatedAt:    NowUTC(),
				ExpiresAt:    expiresAt,
			},
		}
		s.messageQueue[envelope.MailboxToken] = append(s.messageQueue[envelope.MailboxToken], record)
		ids = append(ids, envelopeID)
	}
	return ids, nil
}

func (s *MemoryStore) mailboxOwnerAccountIDLocked(mailboxToken string) (string, error) {
	now := NowUTC()
	for deviceID, device := range s.devices {
		token := device.MailboxToken
		if token == "" {
			token = deviceID
		}
		if token == mailboxToken || deviceID == mailboxToken {
			return device.AccountID, nil
		}
		if device.MailboxTokenPrevious != "" &&
			device.MailboxTokenPrevious == mailboxToken &&
			device.MailboxPreviousExpires.After(now) {
			return device.AccountID, nil
		}
	}
	return "", ErrNotFound
}

func (s *MemoryStore) ListQueuedEnvelopes(mailboxTokens []string, limit int) ([]QueuedEnvelopeRecord, error) {
	if s.relayQueue != nil {
		return s.relayQueue.ListQueuedEnvelopes(mailboxTokens, limit)
	}
	s.mu.RLock()
	defer s.mu.RUnlock()

	if len(mailboxTokens) == 0 {
		return []QueuedEnvelopeRecord{}, nil
	}
	if limit <= 0 {
		limit = 50
	}
	now := NowUTC()
	envelopes := make([]QueuedEnvelopeRecord, 0, limit)
	for _, mailboxToken := range mailboxTokens {
		for _, item := range s.messageQueue[mailboxToken] {
			if !item.ExpiresAt.After(now) {
				continue
			}
			envelopes = append(envelopes, item.QueuedEnvelopeRecord)
		}
	}
	sort.Slice(envelopes, func(i, j int) bool {
		return envelopes[i].CreatedAt.Before(envelopes[j].CreatedAt)
	})
	if len(envelopes) > limit {
		envelopes = envelopes[:limit]
	}
	return envelopes, nil
}

func (s *MemoryStore) AckQueuedEnvelopes(mailboxTokens []string, envelopeIDs []string) (int, error) {
	if s.relayQueue != nil {
		return s.relayQueue.AckQueuedEnvelopes(mailboxTokens, envelopeIDs)
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	if len(envelopeIDs) == 0 || len(mailboxTokens) == 0 {
		return 0, nil
	}
	tokenSet := make(map[string]struct{}, len(mailboxTokens))
	for _, token := range mailboxTokens {
		tokenSet[token] = struct{}{}
	}
	toDelete := make(map[string]struct{}, len(envelopeIDs))
	for _, id := range envelopeIDs {
		toDelete[id] = struct{}{}
	}

	deleted := 0
	for mailboxToken := range tokenSet {
		queued := s.messageQueue[mailboxToken]
		remaining := make([]queuedEnvelopeRecord, 0, len(queued))
		for _, item := range queued {
			if _, ok := toDelete[item.EnvelopeID]; ok {
				deleted++
				continue
			}
			remaining = append(remaining, item)
		}
		s.messageQueue[mailboxToken] = remaining
	}
	return deleted, nil
}
