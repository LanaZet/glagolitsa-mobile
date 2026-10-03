// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"strings"

	"glagolitsa/server/internal/model"
)

func (s *MemoryStore) CreateProfile(userID, username string, user model.User) (model.User, error) {
	user.ID = userID
	user.Username = strings.ToLower(strings.TrimSpace(username))
	return user, nil
}

func (s *MemoryStore) GetProfile(userID string) (model.User, error) {
	return s.GetUserByID(userID)
}

func (s *MemoryStore) UpdateProfile(userID string, update model.UpdateProfileRequest) (model.User, error) {
	return s.UpdateUserProfile(userID, update)
}

func (s *MemoryStore) SearchProfiles(query, excludeUserID string, limit int) ([]model.UserSearchHit, error) {
	return s.searchProfileHits(query, excludeUserID, limit, false)
}

func (s *MemoryStore) AutocompleteProfiles(query, excludeUserID string, limit int) ([]model.UserSearchHit, error) {
	return s.searchProfileHits(query, excludeUserID, limit, true)
}

func (s *MemoryStore) LookupProfileByUsername(username, excludeUserID string) (model.UserSearchHit, error) {
	username = strings.ToLower(strings.TrimSpace(username))
	if username == "" {
		return model.UserSearchHit{}, ErrNotFound
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, record := range s.users {
		if record.User.ID == excludeUserID {
			continue
		}
		if record.AccountStatus != model.AccountStatusActive {
			continue
		}
		if record.User.Username == username {
			return userSearchHitFromRecord(record), nil
		}
	}
	return model.UserSearchHit{}, ErrNotFound
}

func (s *MemoryStore) GetUsername(userID string) (string, error) {
	user, err := s.GetUserByID(userID)
	if err != nil {
		return "", err
	}
	return user.Username, nil
}

func (s *MemoryStore) GetProfilesByIDs(userIDs []string, excludeUserID string) ([]model.UserSearchHit, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	seen := make(map[string]struct{})
	out := make([]model.UserSearchHit, 0)
	for _, id := range userIDs {
		id = strings.TrimSpace(id)
		if id == "" || id == excludeUserID {
			continue
		}
		if _, ok := seen[id]; ok {
			continue
		}
		seen[id] = struct{}{}
		record, ok := s.users[id]
		if !ok || record.AccountStatus != model.AccountStatusActive {
			continue
		}
		out = append(out, userSearchHitFromRecord(record))
		if len(out) >= 50 {
			break
		}
	}
	return out, nil
}

func userSearchHitFromRecord(record *userRecord) model.UserSearchHit {
	return model.UserSearchHit{
		ID:          record.User.ID,
		Username:    record.User.Username,
		DisplayName: record.User.DisplayName,
		Status:      record.User.Status,
		Bio:         record.User.Bio,
		AvatarURL:   record.User.AvatarURL,
	}
}

func searchHitsToUsers(hits []model.UserSearchHit) []model.User {
	users := make([]model.User, len(hits))
	for i, hit := range hits {
		users[i] = model.User{
			ID:          hit.ID,
			Username:    hit.Username,
			DisplayName: hit.DisplayName,
			Status:      hit.Status,
			Bio:         hit.Bio,
			AvatarURL:   hit.AvatarURL,
		}
	}
	return users
}
