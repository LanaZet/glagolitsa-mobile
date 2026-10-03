// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"sort"
	"time"

	"glagolitsa/server/internal/model"
)

func (s *MemoryStore) GetPresencePrivacy(userID string) (model.PresencePrivacySettings, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if rec, ok := s.presencePrivacy[userID]; ok {
		return rec, nil
	}
	return model.PresencePrivacySettings{
		OnlineVisibility:   model.PresenceVisibilityContacts,
		LastSeenVisibility: model.PresenceVisibilityContacts,
	}, nil
}

func (s *MemoryStore) SetPresencePrivacy(userID string, settings model.PresencePrivacySettings) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.presencePrivacy == nil {
		s.presencePrivacy = make(map[string]model.PresencePrivacySettings)
	}
	s.presencePrivacy[userID] = settings
	return nil
}

func (s *MemoryStore) GetLastSeenAt(userID string) (*time.Time, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if rec, ok := s.userPresence[userID]; ok {
		return rec.LastSeenAt, nil
	}
	return nil, nil
}

func (s *MemoryStore) SetLastSeenAt(userID string, at time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.userPresence == nil {
		s.userPresence = make(map[string]memPresenceRecord)
	}
	rec := s.userPresence[userID]
	rec.Status = model.PresenceOffline
	rec.LastSeenAt = &at
	rec.ShowLastSeen = true
	s.userPresence[userID] = rec
	return nil
}

func (s *MemoryStore) ListContactUserIDs(userID string) ([]string, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	seen := make(map[string]struct{})
	for _, chat := range s.chats {
		if chat.Type != model.ChatTypeDM || !chatHasMember(*chat, userID) {
			continue
		}
		for _, memberID := range chat.MemberIDs {
			if memberID != userID {
				seen[memberID] = struct{}{}
			}
		}
	}
	out := make([]string, 0, len(seen))
	for id := range seen {
		out = append(out, id)
	}
	sort.Strings(out)
	return out, nil
}

func (s *MemoryStore) GetChatMemberIDs(chatID, viewerID string) ([]string, error) {
	chat, err := s.GetChat(chatID, viewerID)
	if err != nil {
		return nil, err
	}
	return chat.MemberIDs, nil
}
