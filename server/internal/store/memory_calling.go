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

func (s *MemoryStore) CreateCall(call model.CallSession) (model.CallSession, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	copy := normalizeCallDefaults(call)
	s.calls[call.ID] = &copy
	return copy, nil
}

func (s *MemoryStore) CreateCallIfAvailable(call model.CallSession, participantUserIDs []string) (model.CallSession, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	userIDs := uniqueNonEmptyStrings(participantUserIDs)
	if len(userIDs) == 0 {
		userIDs = uniqueNonEmptyStrings([]string{call.CallerID, call.CalleeID})
	}
	liveUsers := make(map[string]struct{}, len(userIDs))
	for _, id := range userIDs {
		liveUsers[id] = struct{}{}
	}
	for _, existing := range s.calls {
		if !isLiveCallStatus(existing.Status) {
			continue
		}
		if _, ok := liveUsers[existing.CallerID]; ok {
			return model.CallSession{}, ErrAlreadyExists
		}
		if _, ok := liveUsers[existing.CalleeID]; ok {
			return model.CallSession{}, ErrAlreadyExists
		}
		for _, p := range s.callParticipants[existing.ID] {
			if p.LeftAt != nil {
				continue
			}
			if _, ok := liveUsers[p.UserID]; ok {
				return model.CallSession{}, ErrAlreadyExists
			}
		}
	}
	copy := normalizeCallDefaults(call)
	s.calls[call.ID] = &copy
	return copy, nil
}

func normalizeCallDefaults(call model.CallSession) model.CallSession {
	copy := call
	if copy.CallScope == "" {
		copy.CallScope = model.CallScopeDM
	}
	if copy.StartedByUserID == "" {
		copy.StartedByUserID = copy.CallerID
	}
	if copy.SelectedRegion == "" {
		copy.SelectedRegion = "primary"
	}
	if copy.RouteClass == "" {
		copy.RouteClass = "single_region"
	}
	if copy.PolicyVersion == 0 {
		copy.PolicyVersion = 1
	}
	return copy
}

func (s *MemoryStore) GetCall(callID string) (model.CallSession, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	call, ok := s.calls[callID]
	if !ok {
		return model.CallSession{}, ErrNotFound
	}
	return *call, nil
}

func (s *MemoryStore) FindLiveCallForChat(chatID string) (model.CallSession, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if chatID == "" {
		return model.CallSession{}, ErrNotFound
	}
	var best *model.CallSession
	for _, call := range s.calls {
		if call.ChatID != chatID || !isLiveCallStatus(call.Status) {
			continue
		}
		if best == nil || call.CreatedAt.After(best.CreatedAt) {
			copy := *call
			best = &copy
		}
	}
	if best == nil {
		return model.CallSession{}, ErrNotFound
	}
	return *best, nil
}

func (s *MemoryStore) ListCallsForUser(userID string, limit int) ([]model.CallSession, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if limit <= 0 {
		limit = 50
	}

	seen := map[string]struct{}{}
	calls := make([]model.CallSession, 0)
	for _, call := range s.calls {
		if call.CallerID == userID || call.CalleeID == userID {
			calls = append(calls, *call)
			seen[call.ID] = struct{}{}
			continue
		}
		for _, p := range s.callParticipants[call.ID] {
			if p.UserID == userID {
				if _, ok := seen[call.ID]; !ok {
					calls = append(calls, *call)
					seen[call.ID] = struct{}{}
				}
				break
			}
		}
	}
	sort.Slice(calls, func(i, j int) bool {
		return calls[i].CreatedAt.After(calls[j].CreatedAt)
	})
	if len(calls) > limit {
		calls = calls[:limit]
	}
	return calls, nil
}

func (s *MemoryStore) UpdateCallStatus(callID, status string, at time.Time) (model.CallSession, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	call, ok := s.calls[callID]
	if !ok {
		return model.CallSession{}, ErrNotFound
	}
	call.Status = status
	switch status {
	case model.CallStatusConnecting:
		call.AcceptedAt = &at
	case model.CallStatusRejected, model.CallStatusMissed:
		call.EndedAt = &at
	}
	return *call, nil
}

func (s *MemoryStore) MarkCallConnected(callID string, at time.Time) (model.CallSession, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	call, ok := s.calls[callID]
	if !ok {
		return model.CallSession{}, ErrNotFound
	}
	switch call.Status {
	case model.CallStatusEnded, model.CallStatusRejected, model.CallStatusMissed:
		return *call, nil
	}
	call.Status = model.CallStatusActive
	call.ConnectedAt = &at
	return *call, nil
}

func (s *MemoryStore) EndCall(callID string, at time.Time) (model.CallSession, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	call, ok := s.calls[callID]
	if !ok {
		return model.CallSession{}, ErrNotFound
	}
	call.Status = model.CallStatusEnded
	call.EndedAt = &at
	start := call.ConnectedAt
	if start == nil {
		start = call.AcceptedAt
	}
	if start == nil {
		start = &call.CreatedAt
	}
	call.DurationSec = int(at.Sub(*start).Seconds())
	if call.DurationSec < 0 {
		call.DurationSec = 0
	}
	return *call, nil
}

func (s *MemoryStore) SetLowBandwidthMode(callID string, enabled bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	call, ok := s.calls[callID]
	if !ok {
		return ErrNotFound
	}
	call.LowBandwidthMode = enabled
	return nil
}

func (s *MemoryStore) UpsertParticipant(callID, userID, deviceID string, joinedAt, leftAt *time.Time) error {
	return s.UpsertCallParticipant(model.CallParticipant{
		CallID:      callID,
		UserID:      userID,
		DeviceID:    deviceID,
		Role:        model.CallParticipantRoleJoined,
		InviteState: model.CallInviteStateJoined,
		MediaState:  model.CallMediaAudioOnly,
		JoinedAt:    joinedAt,
		LeftAt:      leftAt,
	})
}

func (s *MemoryStore) UpsertCallParticipant(p model.CallParticipant) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, ok := s.calls[p.CallID]; !ok {
		return ErrNotFound
	}
	if p.Role == "" {
		p.Role = model.CallParticipantRoleJoined
	}
	if p.InviteState == "" {
		p.InviteState = model.CallInviteStateJoined
	}
	if p.MediaState == "" {
		p.MediaState = model.CallMediaAudioOnly
	}

	participants := s.callParticipants[p.CallID]
	for i := range participants {
		if participants[i].UserID == p.UserID && participants[i].DeviceID == p.DeviceID {
			if p.Role != "" {
				participants[i].Role = p.Role
			}
			if p.InviteState != "" {
				participants[i].InviteState = p.InviteState
			}
			if p.MediaState != "" {
				participants[i].MediaState = p.MediaState
			}
			if p.JoinedAt != nil {
				participants[i].JoinedAt = p.JoinedAt
			}
			if p.LeftAt != nil {
				participants[i].LeftAt = p.LeftAt
			}
			s.callParticipants[p.CallID] = participants
			return nil
		}
	}
	s.callParticipants[p.CallID] = append(participants, memCallParticipant{
		CallID:      p.CallID,
		UserID:      p.UserID,
		DeviceID:    p.DeviceID,
		Role:        p.Role,
		InviteState: p.InviteState,
		MediaState:  p.MediaState,
		JoinedAt:    p.JoinedAt,
		LeftAt:      p.LeftAt,
	})
	return nil
}

func (s *MemoryStore) ListCallParticipants(callID string) ([]model.CallParticipant, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if _, ok := s.calls[callID]; !ok {
		return nil, ErrNotFound
	}
	out := make([]model.CallParticipant, 0, len(s.callParticipants[callID]))
	for _, p := range s.callParticipants[callID] {
		out = append(out, model.CallParticipant{
			CallID:      p.CallID,
			UserID:      p.UserID,
			DeviceID:    p.DeviceID,
			Role:        p.Role,
			InviteState: p.InviteState,
			MediaState:  p.MediaState,
			JoinedAt:    p.JoinedAt,
			LeftAt:      p.LeftAt,
		})
	}
	return out, nil
}

func (s *MemoryStore) IsCallParticipant(callID, userID string) (bool, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	call, ok := s.calls[callID]
	if !ok {
		return false, ErrNotFound
	}
	if call.CallerID == userID || call.CalleeID == userID {
		return true, nil
	}
	for _, p := range s.callParticipants[callID] {
		if p.UserID == userID {
			return true, nil
		}
	}
	return false, nil
}

// IsGroupMember checks chat membership for group-call invites.
func (s *MemoryStore) IsGroupMember(chatID, userID string) (bool, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if chatID == "" || userID == "" {
		return false, nil
	}
	if members, ok := s.groupMembers[chatID]; ok {
		_, exists := members[userID]
		return exists, nil
	}
	chat, ok := s.chats[chatID]
	if !ok {
		return false, nil
	}
	for _, id := range chat.MemberIDs {
		if id == userID {
			return true, nil
		}
	}
	return false, nil
}

func (s *MemoryStore) ListStaleCalls(ringingBefore, connectingBefore, activeBefore time.Time, limit int) ([]model.CallSession, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if limit <= 0 {
		limit = 100
	}
	out := make([]model.CallSession, 0)
	for _, call := range s.calls {
		switch call.Status {
		case model.CallStatusRinging:
			if call.CreatedAt.Before(ringingBefore) {
				out = append(out, *call)
			}
		case model.CallStatusConnecting:
			t := call.CreatedAt
			if call.AcceptedAt != nil {
				t = *call.AcceptedAt
			}
			if t.Before(connectingBefore) {
				out = append(out, *call)
			}
		case model.CallStatusActive:
			t := call.CreatedAt
			if call.ConnectedAt != nil {
				t = *call.ConnectedAt
			} else if call.AcceptedAt != nil {
				t = *call.AcceptedAt
			}
			if t.Before(activeBefore) {
				out = append(out, *call)
			}
		}
		if len(out) >= limit {
			break
		}
	}
	return out, nil
}

func (s *MemoryStore) CreateCallInvite(inv model.CallInvite) (model.CallInvite, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.calls[inv.CallID]; !ok {
		return model.CallInvite{}, ErrNotFound
	}
	if inv.ID == "" {
		inv.ID = uuid.NewString()
	}
	if inv.State == "" {
		inv.State = model.CallInvitePending
	}
	if inv.CreatedAt.IsZero() {
		inv.CreatedAt = NowUTC()
	}
	list := s.callInvites[inv.CallID]
	for i := range list {
		if list[i].InvitedUserID == inv.InvitedUserID {
			list[i] = memCallInvite{
				ID:              inv.ID,
				CallID:          inv.CallID,
				InvitedUserID:   inv.InvitedUserID,
				InvitedByUserID: inv.InvitedByUserID,
				State:           inv.State,
				CreatedAt:       inv.CreatedAt,
				ExpiresAt:       inv.ExpiresAt,
			}
			s.callInvites[inv.CallID] = list
			return inv, nil
		}
	}
	s.callInvites[inv.CallID] = append(list, memCallInvite{
		ID:              inv.ID,
		CallID:          inv.CallID,
		InvitedUserID:   inv.InvitedUserID,
		InvitedByUserID: inv.InvitedByUserID,
		State:           inv.State,
		CreatedAt:       inv.CreatedAt,
		ExpiresAt:       inv.ExpiresAt,
	})
	return inv, nil
}

func (s *MemoryStore) StoreCallKeyOffers(callID string, offers []CallKeyOfferRecord) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, ok := s.calls[callID]; !ok {
		return ErrNotFound
	}

	now := NowUTC()
	existing := s.callKeyOffers[callID]
	for _, offer := range offers {
		replaced := false
		for i := range existing {
			if existing[i].SourceDeviceID == offer.SourceDeviceID &&
				existing[i].TargetDeviceID == offer.TargetDeviceID {
				existing[i].EnvelopeType = offer.EnvelopeType
				existing[i].EncryptedKey = append([]byte(nil), offer.EncryptedKey...)
				existing[i].CreatedAt = now
				replaced = true
				break
			}
		}
		if !replaced {
			existing = append(existing, memCallKeyOffer{
				SourceUserID:   offer.SourceUserID,
				SourceDeviceID: offer.SourceDeviceID,
				TargetUserID:   offer.TargetUserID,
				TargetDeviceID: offer.TargetDeviceID,
				EnvelopeType:   offer.EnvelopeType,
				EncryptedKey:   append([]byte(nil), offer.EncryptedKey...),
				CreatedAt:      now,
			})
		}
	}
	s.callKeyOffers[callID] = existing
	return nil
}

func (s *MemoryStore) ListCallKeyOffersForDevice(callID, userID, deviceID string) ([]model.CallKeyOffer, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if _, ok := s.calls[callID]; !ok {
		return nil, ErrNotFound
	}

	offers := make([]model.CallKeyOffer, 0)
	for _, offer := range s.callKeyOffers[callID] {
		if offer.TargetUserID != userID || offer.TargetDeviceID != deviceID {
			continue
		}
		offers = append(offers, model.CallKeyOffer{
			SourceUserID:   offer.SourceUserID,
			SourceDeviceID: offer.SourceDeviceID,
			TargetUserID:   offer.TargetUserID,
			TargetDeviceID: offer.TargetDeviceID,
			EnvelopeType:   offer.EnvelopeType,
			EncryptedKey:   base64.StdEncoding.EncodeToString(offer.EncryptedKey),
			CreatedAt:      offer.CreatedAt,
		})
	}
	sort.Slice(offers, func(i, j int) bool {
		return offers[i].CreatedAt.Before(offers[j].CreatedAt)
	})
	return offers, nil
}
