// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package profile

import "glagolitsa/server/internal/model"

// Store — контракт Profile Service.
type Store interface {
	CreateProfile(userID, username string, user model.User) (model.User, error)
	GetProfile(userID string) (model.User, error)
	UpdateProfile(userID string, update model.UpdateProfileRequest) (model.User, error)
	SearchProfiles(query, excludeUserID string, limit int) ([]model.UserSearchHit, error)
	AutocompleteProfiles(query, excludeUserID string, limit int) ([]model.UserSearchHit, error)
	LookupProfileByUsername(username, excludeUserID string) (model.UserSearchHit, error)
	// GetProfilesByIDs — Mattermost-style batch card for avatars in chat list (id/username/display/avatar).
	GetProfilesByIDs(userIDs []string, excludeUserID string) ([]model.UserSearchHit, error)
	GetUsername(userID string) (string, error)
}