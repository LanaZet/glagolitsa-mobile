// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package presence

import (
	"strings"
	"sync"
	"time"
)

type memoryEntry struct {
	value   string
	expires time.Time
}

type MemoryStore struct {
	cfg Config
	mu  sync.RWMutex

	userStatus map[string]memoryEntry
	devices    map[string]map[string]time.Time // userID -> deviceID -> expires
	typing     map[string]map[string]time.Time // chatID -> userID -> expires
	recording  map[string]map[string]memoryEntry
	inCall     map[string]memoryEntry
}

func NewMemoryStore(cfg Config) *MemoryStore {
	return &MemoryStore{
		cfg:        cfg,
		userStatus: make(map[string]memoryEntry),
		devices:    make(map[string]map[string]time.Time),
		typing:     make(map[string]map[string]time.Time),
		recording:  make(map[string]map[string]memoryEntry),
		inCall:     make(map[string]memoryEntry),
	}
}

func (s *MemoryStore) Close() error { return nil }

func (s *MemoryStore) SetUserOnline(userID, deviceID string, ttl time.Duration) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.purgeExpiredLocked()
	wasOffline := !s.isOnlineLocked(userID)
	exp := time.Now().Add(ttl)
	s.userStatus[userID] = memoryEntry{value: "online", expires: exp}
	if deviceID != "" {
		if s.devices[userID] == nil {
			s.devices[userID] = make(map[string]time.Time)
		}
		s.devices[userID][deviceID] = exp
	}
	return wasOffline, nil
}

func (s *MemoryStore) TouchDevice(userID, deviceID string, ttl time.Duration) error {
	if deviceID == "" {
		return nil
	}
	_, err := s.SetUserOnline(userID, deviceID, ttl)
	return err
}

func (s *MemoryStore) RemoveDevice(userID, deviceID string) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.purgeExpiredLocked()
	devs := s.devices[userID]
	if devs != nil && deviceID != "" {
		delete(devs, deviceID)
	}
	left := len(devs)
	if left == 0 {
		delete(s.devices, userID)
		if call, ok := s.inCall[userID]; ok && time.Now().Before(call.expires) {
			s.userStatus[userID] = memoryEntry{value: "in_call", expires: call.expires}
		} else {
			delete(s.userStatus, userID)
		}
	}
	return left, nil
}

func (s *MemoryStore) GetUserStatus(userID string) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.purgeExpiredLocked()
	if call, ok := s.inCall[userID]; ok && time.Now().Before(call.expires) {
		return "in_call", nil
	}
	if entry, ok := s.userStatus[userID]; ok && time.Now().Before(entry.expires) {
		return entry.value, nil
	}
	return "offline", nil
}

func (s *MemoryStore) ListActiveDevices(userID string) ([]string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.purgeExpiredLocked()
	now := time.Now()
	out := make([]string, 0)
	for deviceID, exp := range s.devices[userID] {
		if now.Before(exp) {
			out = append(out, deviceID)
		}
	}
	return out, nil
}

func (s *MemoryStore) SetTyping(chatID, userID string, ttl time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.typing[chatID] == nil {
		s.typing[chatID] = make(map[string]time.Time)
	}
	s.typing[chatID][userID] = time.Now().Add(ttl)
	return nil
}

func (s *MemoryStore) ClearTyping(chatID, userID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if chat, ok := s.typing[chatID]; ok {
		delete(chat, userID)
	}
	return nil
}

func (s *MemoryStore) ListTyping(chatID string) ([]string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	out := make([]string, 0)
	for userID, exp := range s.typing[chatID] {
		if now.Before(exp) {
			out = append(out, userID)
		}
	}
	return out, nil
}

func (s *MemoryStore) SetRecording(chatID, userID, kind string, ttl time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.recording[chatID] == nil {
		s.recording[chatID] = make(map[string]memoryEntry)
	}
	kind = strings.TrimSpace(kind)
	if kind == "" {
		kind = "voice"
	}
	s.recording[chatID][userID] = memoryEntry{value: kind, expires: time.Now().Add(ttl)}
	return nil
}

func (s *MemoryStore) ClearRecording(chatID, userID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if chat, ok := s.recording[chatID]; ok {
		delete(chat, userID)
	}
	return nil
}

func (s *MemoryStore) ListRecording(chatID string) (map[string]string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	out := make(map[string]string)
	for userID, entry := range s.recording[chatID] {
		if now.Before(entry.expires) {
			out[userID] = entry.value
		}
	}
	return out, nil
}

func (s *MemoryStore) SetInCall(userID, callID string, ttl time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	exp := time.Now().Add(ttl)
	s.inCall[userID] = memoryEntry{value: callID, expires: exp}
	s.userStatus[userID] = memoryEntry{value: "in_call", expires: exp}
	return nil
}

func (s *MemoryStore) ClearInCall(userID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.inCall, userID)
	if len(s.devices[userID]) > 0 {
		exp := time.Now().Add(s.cfg.OnlineTTL)
		s.userStatus[userID] = memoryEntry{value: "online", expires: exp}
	} else {
		delete(s.userStatus, userID)
	}
	return nil
}

func (s *MemoryStore) GetInCall(userID string) (string, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	entry, ok := s.inCall[userID]
	if !ok || time.Now().After(entry.expires) {
		return "", false, nil
	}
	return entry.value, true, nil
}

func (s *MemoryStore) isOnlineLocked(userID string) bool {
	if entry, ok := s.userStatus[userID]; ok && time.Now().Before(entry.expires) {
		return entry.value == "online" || entry.value == "in_call"
	}
	if call, ok := s.inCall[userID]; ok && time.Now().Before(call.expires) {
		return true
	}
	return len(s.devices[userID]) > 0
}

func (s *MemoryStore) purgeExpiredLocked() {
	now := time.Now()
	for userID, entry := range s.userStatus {
		if now.After(entry.expires) {
			delete(s.userStatus, userID)
		}
	}
	for userID, devs := range s.devices {
		for deviceID, exp := range devs {
			if now.After(exp) {
				delete(devs, deviceID)
			}
		}
		if len(devs) == 0 {
			delete(s.devices, userID)
		}
	}
	for chatID, users := range s.typing {
		for userID, exp := range users {
			if now.After(exp) {
				delete(users, userID)
			}
		}
		if len(users) == 0 {
			delete(s.typing, chatID)
		}
	}
	for chatID, users := range s.recording {
		for userID, entry := range users {
			if now.After(entry.expires) {
				delete(users, userID)
			}
		}
		if len(users) == 0 {
			delete(s.recording, chatID)
		}
	}
	for userID, entry := range s.inCall {
		if now.After(entry.expires) {
			delete(s.inCall, userID)
		}
	}
}