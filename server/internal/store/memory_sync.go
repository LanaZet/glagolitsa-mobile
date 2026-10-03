// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"sort"
	"time"

	"github.com/google/uuid"
)

func (s *MemoryStore) AppendSyncEvent(input AppendSyncEventInput) (SyncEventRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.appendSyncEventLocked(input), nil
}

// appendSyncEventLocked требует удержания s.mu (Lock).
func (s *MemoryStore) appendSyncEventLocked(input AppendSyncEventInput) SyncEventRecord {
	if s.syncEvents == nil {
		s.syncEvents = make([]SyncEventRecord, 0)
	}
	if s.syncEventSeq == 0 {
		s.syncEventSeq = 1000
	}
	s.syncEventSeq++
	if input.Version <= 0 {
		input.Version = 1
	}
	record := SyncEventRecord{
		EventID: s.syncEventSeq, ScopeUserID: input.ScopeUserID, ChatID: input.ChatID,
		MessageID: input.MessageID, Operation: input.Operation, Version: input.Version,
		Ciphertext: append([]byte(nil), input.Ciphertext...), Metadata: input.Metadata,
		ActorID: input.ActorID, CreatedAt: NowUTC(),
	}
	s.syncEvents = append(s.syncEvents, record)
	return record
}

func (s *MemoryStore) appendSyncEventFromChat(event ChatEventInput) {
	operation := mapChatEventToSyncOperation(event.EventType)
	if operation == "" {
		return
	}
	s.appendSyncEventLocked(AppendSyncEventInput{
		ChatID: event.ChatID, MessageID: event.EntityID, Operation: operation,
		Version: 1, Metadata: event.Metadata, ActorID: event.ActorID,
	})
}

func (s *MemoryStore) ListSyncEvents(userID string, afterEventID int64, limit int) ([]SyncEventRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if limit <= 0 {
		limit = 100
	}
	var out []SyncEventRecord
	for _, event := range s.syncEvents {
		if event.EventID <= afterEventID {
			continue
		}
		if !syncEventVisibleToUser(*s, event, userID) {
			continue
		}
		out = append(out, event)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].EventID < out[j].EventID })
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

func syncEventVisibleToUser(s MemoryStore, event SyncEventRecord, userID string) bool {
	if event.ScopeUserID == userID {
		return true
	}
	if event.ChatID == "" {
		return false
	}
	chat, ok := s.chats[event.ChatID]
	if !ok {
		return false
	}
	return chatHasMember(*chat, userID)
}

func (s *MemoryStore) GetLatestSyncEventID(userID string) (int64, error) {
	events, err := s.ListSyncEvents(userID, 0, 1_000_000)
	if err != nil {
		return 0, err
	}
	var max int64
	for _, event := range events {
		if event.EventID > max {
			max = event.EventID
		}
	}
	return max, nil
}

func (s *MemoryStore) RegisterSyncDevice(userID string, input RegisterSyncDeviceInput) (SyncDeviceRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.syncDevices == nil {
		s.syncDevices = make(map[string]map[string]SyncDeviceRecord)
	}
	devices := s.syncDevices[userID]
	if devices == nil {
		devices = make(map[string]SyncDeviceRecord)
	}
	profile := input.SyncProfile
	if profile == "" {
		profile = SyncProfileBalanced
	}
	now := NowUTC()
	record, ok := devices[input.DeviceID]
	if !ok {
		record = SyncDeviceRecord{
			ID: uuid.NewString(), UserID: userID, DeviceID: input.DeviceID,
			SyncProfile: profile, RegisteredAt: now,
		}
	}
	record.Label = input.Label
	record.SyncProfile = profile
	record.LastSeenAt = now
	devices[input.DeviceID] = record
	s.syncDevices[userID] = devices
	return record, nil
}

func (s *MemoryStore) GetSyncDevice(userID, deviceID string) (SyncDeviceRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if devices, ok := s.syncDevices[userID]; ok {
		if record, ok := devices[deviceID]; ok {
			return record, nil
		}
	}
	return SyncDeviceRecord{}, ErrNotFound
}

func (s *MemoryStore) AckSyncEvents(userID, deviceID string, lastEventID int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	devices := s.syncDevices[userID]
	record, ok := devices[deviceID]
	if !ok {
		return ErrNotFound
	}
	if lastEventID > record.LastAckedEventID {
		record.LastAckedEventID = lastEventID
	}
	record.LastSeenAt = NowUTC()
	devices[deviceID] = record
	s.syncDevices[userID] = devices
	return nil
}

func (s *MemoryStore) SaveSyncSnapshot(userID string, eventID int64, data []byte) (SyncSnapshotRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.syncSnapshots == nil {
		s.syncSnapshots = make(map[string][]SyncSnapshotRecord)
	}
	record := SyncSnapshotRecord{
		ID: uuid.NewString(), UserID: userID, EventID: eventID,
		SnapshotData: append([]byte(nil), data...), CreatedAt: NowUTC(),
	}
	s.syncSnapshots[userID] = append(s.syncSnapshots[userID], record)
	return record, nil
}

func (s *MemoryStore) GetLatestSyncSnapshot(userID string) (SyncSnapshotRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	snaps := s.syncSnapshots[userID]
	if len(snaps) == 0 {
		return SyncSnapshotRecord{}, ErrNotFound
	}
	best := snaps[0]
	for _, snap := range snaps[1:] {
		if snap.EventID > best.EventID {
			best = snap
		}
	}
	return best, nil
}

func (s *MemoryStore) EnqueueSyncOffline(userID string, input EnqueueSyncEventInput) (SyncOfflineQueueRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.syncOfflineQueue == nil {
		s.syncOfflineQueue = make(map[string]SyncOfflineQueueRecord)
	}
	key := userID + ":" + input.DeviceID + ":" + input.ClientEventID
	if _, exists := s.syncOfflineQueue[key]; exists {
		return SyncOfflineQueueRecord{}, ErrAlreadyExists
	}
	record := SyncOfflineQueueRecord{
		ID: uuid.NewString(), UserID: userID, DeviceID: input.DeviceID,
		ClientEventID: input.ClientEventID, Operation: input.Operation,
		ChatID: input.ChatID, MessageID: input.MessageID, Version: input.Version,
		Ciphertext: input.Ciphertext, Metadata: input.Metadata,
		Status: SyncQueuePending, NextRetryAt: NowUTC(), CreatedAt: NowUTC(),
	}
	s.syncOfflineQueue[key] = record
	return record, nil
}

func (s *MemoryStore) ListSyncOfflineDue(limit int, before time.Time) ([]SyncOfflineQueueRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var out []SyncOfflineQueueRecord
	for _, record := range s.syncOfflineQueue {
		if record.Status != SyncQueuePending && record.Status != SyncQueueFailed {
			continue
		}
		if record.NextRetryAt.After(before) {
			continue
		}
		out = append(out, record)
		if len(out) >= limit {
			break
		}
	}
	return out, nil
}

func (s *MemoryStore) MarkSyncOfflineApplied(id string, _ int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for key, record := range s.syncOfflineQueue {
		if record.ID == id {
			record.Status = SyncQueueApplied
			s.syncOfflineQueue[key] = record
			return nil
		}
	}
	return ErrNotFound
}

func (s *MemoryStore) MarkSyncOfflineRetry(id, lastError string, attempts int, nextRetry time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for key, record := range s.syncOfflineQueue {
		if record.ID == id {
			record.Status = SyncQueueFailed
			record.Attempts = attempts
			record.NextRetryAt = nextRetry
			record.LastError = lastError
			s.syncOfflineQueue[key] = record
			return nil
		}
	}
	return ErrNotFound
}

func (s *MemoryStore) GetSyncEventVersion(chatID, messageID, operation string) (int, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	max := 0
	for _, event := range s.syncEvents {
		if event.ChatID == chatID && event.MessageID == messageID && event.Operation == operation {
			if event.Version > max {
				max = event.Version
			}
		}
	}
	return max, nil
}
