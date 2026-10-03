// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"glagolitsa/server/internal/model"
)

func (s *MemoryStore) ensureReactionsLocked() {
	if s.reactions == nil {
		s.reactions = make(map[string]map[string]string)
	}
}

func (s *MemoryStore) FindMessage(chatID, messageID string) (model.Message, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, m := range s.messages[chatID] {
		if m.ID == messageID {
			return m, nil
		}
	}
	// Search all chats if chatID empty
	if chatID == "" {
		for _, list := range s.messages {
			for _, m := range list {
				if m.ID == messageID {
					return m, nil
				}
			}
		}
	}
	return model.Message{}, ErrNotFound
}

func (s *MemoryStore) SetMessageReaction(messageID, userID, emoji string) ([]model.ReactionSummary, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.ensureReactionsLocked()
	if s.reactions[messageID] == nil {
		s.reactions[messageID] = make(map[string]string)
	}
	s.reactions[messageID][userID] = emoji
	return s.summarizeLocked(messageID, userID), nil
}

func (s *MemoryStore) ClearMessageReaction(messageID, userID string) ([]model.ReactionSummary, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.ensureReactionsLocked()
	if s.reactions[messageID] != nil {
		delete(s.reactions[messageID], userID)
	}
	return s.summarizeLocked(messageID, userID), nil
}

func (s *MemoryStore) ListMessageReactions(messageID, viewerID string) ([]model.ReactionSummary, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.summarizeLocked(messageID, viewerID), nil
}

func (s *MemoryStore) summarizeLocked(messageID, viewerID string) []model.ReactionSummary {
	s.ensureReactionsLocked()
	rows := make([]MessageReaction, 0)
	for uid, emoji := range s.reactions[messageID] {
		rows = append(rows, MessageReaction{MessageID: messageID, UserID: uid, Emoji: emoji})
	}
	return SummarizeReactions(rows, viewerID)
}
