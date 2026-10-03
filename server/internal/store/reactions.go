// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"glagolitsa/server/internal/model"
)

// Allowed channel reaction emoji set (v1 fixed).
var AllowedReactionEmojis = map[string]struct{}{
	"👍":  {},
	"❤️": {},
	"🔥":  {},
	"👏":  {},
	"😮":  {},
}

func IsAllowedReactionEmoji(emoji string) bool {
	_, ok := AllowedReactionEmojis[emoji]
	return ok
}

// ReactionStore methods live on MemoryStore / PostgresStore.
type MessageReaction struct {
	MessageID string
	UserID    string
	Emoji     string
}

func SummarizeReactions(rows []MessageReaction, viewerID string) []model.ReactionSummary {
	type bucket struct {
		count int
		me    bool
		users []string
	}
	byEmoji := map[string]*bucket{}
	order := make([]string, 0)
	for _, r := range rows {
		b, ok := byEmoji[r.Emoji]
		if !ok {
			b = &bucket{}
			byEmoji[r.Emoji] = b
			order = append(order, r.Emoji)
		}
		b.count++
		b.users = append(b.users, r.UserID)
		if r.UserID == viewerID {
			b.me = true
		}
	}
	out := make([]model.ReactionSummary, 0, len(order))
	for _, emoji := range order {
		b := byEmoji[emoji]
		out = append(out, model.ReactionSummary{
			Emoji:   emoji,
			Count:   b.count,
			Me:      b.me,
			UserIDs: b.users,
		})
	}
	return out
}
