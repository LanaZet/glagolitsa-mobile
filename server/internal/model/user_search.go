// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package model

// UserSearchHit — public profile card for autocomplete/search (no email or network presence).
type UserSearchHit struct {
	ID          string `json:"id"`
	Username    string `json:"username"`
	DisplayName string `json:"display_name,omitempty"`
	Status      string `json:"status,omitempty"`
	Bio         string `json:"bio,omitempty"`
	AvatarURL   string `json:"avatar_url,omitempty"`
}
