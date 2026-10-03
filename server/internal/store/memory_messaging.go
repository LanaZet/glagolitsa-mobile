// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"sort"
	"time"

	"github.com/google/uuid"

	"glagolitsa/server/internal/model"
)

type memChatEvent struct {
	model.ChatEvent
}

type memDeliveryRecord struct {
	EnvelopeID         string
	RecipientAccountID string
	MailboxToken       string
	State              string
	SizeBucket         int
}

type memPresenceRecord struct {
	Status       string
	LastSeenAt   *time.Time
	ShowLastSeen bool
}

func (s *MemoryStore) AppendChatEvent(event ChatEventInput) (model.ChatEvent, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.chatEvents == nil {
		s.chatEvents = make([]memChatEvent, 0)
	}
	created := model.ChatEvent{
		ID:        uuid.NewString(),
		ChatID:    event.ChatID,
		EventType: event.EventType,
		ActorID:   event.ActorID,
		EntityID:  event.EntityID,
		Metadata:  event.Metadata,
		CreatedAt: NowUTC(),
	}
	s.chatEvents = append(s.chatEvents, memChatEvent{ChatEvent: created})
	s.appendSyncEventFromChat(event)
	if chat, ok := s.chats[event.ChatID]; ok {
		chat.LastMessageAt = created.CreatedAt
	}
	return created, nil
}

func (s *MemoryStore) ListChatEvents(userID string, since time.Time, limit int) ([]model.ChatEvent, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if limit <= 0 {
		limit = 100
	}
	events := make([]model.ChatEvent, 0)
	for _, event := range s.chatEvents {
		if !event.CreatedAt.After(since) {
			continue
		}
		if event.ChatID != "" && !chatHasMember(*s.chats[event.ChatID], userID) {
			continue
		}
		events = append(events, event.ChatEvent)
	}
	sort.Slice(events, func(i, j int) bool { return events[i].CreatedAt.Before(events[j].CreatedAt) })
	if len(events) > limit {
		events = events[:limit]
	}
	return events, nil
}

func (s *MemoryStore) ListChatsUpdatedSince(userID string, since time.Time) ([]model.Chat, error) {
	chats, err := s.ListChatsForUser(userID)
	if err != nil {
		return nil, err
	}
	filtered := make([]model.Chat, 0)
	for _, chat := range chats {
		if chat.LastMessageAt.After(since) || chat.CreatedAt.After(since) {
			filtered = append(filtered, chat)
		}
	}
	return filtered, nil
}

func (s *MemoryStore) SoftDeleteMessage(chatID, messageID, userID string) (time.Time, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	messages := s.messages[chatID]
	for i, message := range messages {
		if message.ID != messageID {
			continue
		}
		chat, ok := s.chats[chatID]
		if !ok {
			return time.Time{}, ErrNotFound
		}
		if !chatHasMember(*chat, userID) {
			return time.Time{}, ErrForbidden
		}
		deletedAt := NowUTC()
		if s.deletedMessages == nil {
			s.deletedMessages = make(map[string]time.Time)
		}
		s.deletedMessages[messageID] = deletedAt
		messages = append(messages[:i], messages[i+1:]...)
		s.messages[chatID] = messages
		return deletedAt, nil
	}
	return time.Time{}, ErrNotFound
}

func (s *MemoryStore) RecordEnvelopeDelivery(envelopeID, recipientAccountID, mailboxToken string, sizeBucket int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.envelopeDelivery == nil {
		s.envelopeDelivery = make(map[string]memDeliveryRecord)
	}
	s.envelopeDelivery[envelopeID] = memDeliveryRecord{
		EnvelopeID:         envelopeID,
		RecipientAccountID: recipientAccountID,
		MailboxToken:       mailboxToken,
		State:              model.DeliveryQueued,
		SizeBucket:         sizeBucket,
	}
	return nil
}

func (s *MemoryStore) MarkEnvelopesFetched(envelopeIDs []string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, id := range envelopeIDs {
		if rec, ok := s.envelopeDelivery[id]; ok && rec.State == model.DeliveryQueued {
			rec.State = model.DeliveryFetched
			s.envelopeDelivery[id] = rec
		}
	}
	return nil
}

func (s *MemoryStore) MarkEnvelopesAcked(envelopeIDs []string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, id := range envelopeIDs {
		if rec, ok := s.envelopeDelivery[id]; ok {
			rec.State = model.DeliveryAcked
			s.envelopeDelivery[id] = rec
		}
	}
	return nil
}

func (s *MemoryStore) IncrementRelayEnvelopeCount(userID string, count int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.messagingLimits == nil {
		s.messagingLimits = make(map[string]int)
	}
	s.messagingLimits[userID] += count
	return nil
}

func (s *MemoryStore) RelayEnvelopeCount24h(userID string) (int, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.messagingLimits[userID], nil
}

func (s *MemoryStore) GetUserPresence(userID string) (model.PresenceResponse, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if rec, ok := s.userPresence[userID]; ok {
		return model.PresenceResponse{
			UserID:       userID,
			Status:       rec.Status,
			LastSeenAt:   rec.LastSeenAt,
			ShowLastSeen: rec.ShowLastSeen,
		}, nil
	}
	return model.PresenceResponse{UserID: userID, Status: model.PresenceOffline, ShowLastSeen: true}, nil
}

func (s *MemoryStore) SetUserPresence(userID, status string, showLastSeen *bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.userPresence == nil {
		s.userPresence = make(map[string]memPresenceRecord)
	}
	rec := s.userPresence[userID]
	rec.Status = status
	if showLastSeen != nil {
		rec.ShowLastSeen = *showLastSeen
	} else if rec.ShowLastSeen == false {
		rec.ShowLastSeen = true
	}
	if status == model.PresenceOffline {
		now := NowUTC()
		rec.LastSeenAt = &now
	}
	s.userPresence[userID] = rec
	return nil
}

func (s *MemoryStore) PurgeExpiredQueue() (int, error) {
	return 0, nil
}
