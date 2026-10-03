// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package presence

import (
	"time"

	"glagolitsa/server/internal/model"
)

// PrivacyStore — настройки приватности и last_seen (не эфемерные).
type PrivacyStore interface {
	GetPresencePrivacy(userID string) (model.PresencePrivacySettings, error)
	SetPresencePrivacy(userID string, settings model.PresencePrivacySettings) error
	GetLastSeenAt(userID string) (*time.Time, error)
	SetLastSeenAt(userID string, at time.Time) error
}

// SocialGraph — кто может видеть события (контакты / участники чата).
type SocialGraph interface {
	ListContactUserIDs(userID string) ([]string, error)
	GetChatMemberIDs(chatID, viewerID string) ([]string, error)
}