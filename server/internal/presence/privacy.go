// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package presence

import (
	"time"

	"glagolitsa/server/internal/model"
)

func normalizeVisibility(raw string) string {
	switch raw {
	case model.PresenceVisibilityAll, model.PresenceVisibilityContacts, model.PresenceVisibilityNobody:
		return raw
	default:
		return model.PresenceVisibilityContacts
	}
}

func (s *Service) canViewOnline(viewerID, targetID string) bool {
	if viewerID == targetID {
		return true
	}
	settings, err := s.privacy.GetPresencePrivacy(targetID)
	if err != nil {
		return false
	}
	switch normalizeVisibility(settings.OnlineVisibility) {
	case model.PresenceVisibilityNobody:
		return false
	case model.PresenceVisibilityAll:
		return true
	default:
		return s.isContact(viewerID, targetID)
	}
}

func (s *Service) canViewLastSeen(viewerID, targetID string) bool {
	if viewerID == targetID {
		return true
	}
	settings, err := s.privacy.GetPresencePrivacy(targetID)
	if err != nil {
		return false
	}
	switch normalizeVisibility(settings.LastSeenVisibility) {
	case model.PresenceVisibilityNobody:
		return false
	case model.PresenceVisibilityAll:
		return true
	default:
		return s.isContact(viewerID, targetID)
	}
}

func (s *Service) isContact(userID, otherID string) bool {
	contacts, err := s.graph.ListContactUserIDs(userID)
	if err != nil {
		return false
	}
	for _, id := range contacts {
		if id == otherID {
			return true
		}
	}
	return false
}

func bucketLastSeen(at time.Time, now time.Time) string {
	if at.IsZero() {
		return model.LastSeenLongAgo
	}
	diff := now.Sub(at)
	if diff < 5*time.Minute {
		return model.LastSeenJustNow
	}
	if diff < 24*time.Hour {
		return model.LastSeenRecently
	}
	if sameDay(at, now) {
		return model.LastSeenToday
	}
	if diff < 7*24*time.Hour {
		return model.LastSeenThisWeek
	}
	return model.LastSeenLongAgo
}

func sameDay(a, b time.Time) bool {
	ay, am, ad := a.Date()
	by, bm, bd := b.Date()
	return ay == by && am == bm && ad == bd
}