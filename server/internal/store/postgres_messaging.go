// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"glagolitsa/server/internal/model"
)

type ChatEventInput struct {
	ChatID    string
	EventType string
	ActorID   string
	EntityID  string
	Metadata  map[string]any
}

func (s *PostgresStore) AppendChatEvent(event ChatEventInput) (model.ChatEvent, error) {
	meta, _ := json.Marshal(event.Metadata)
	id := uuid.NewString()
	createdAt := NowUTC()
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO chat_events (id, chat_id, event_type, actor_id, entity_id, metadata, created_at)
		VALUES ($1, NULLIF($2, '')::uuid, $3, NULLIF($4, '')::uuid, $5, $6, $7)
	`, id, event.ChatID, event.EventType, event.ActorID, event.EntityID, meta, createdAt)
	if err != nil {
		return model.ChatEvent{}, err
	}
	if event.ChatID != "" {
		_, _ = s.pool.Exec(context.Background(), `
			UPDATE chats SET updated_at = $2 WHERE id = $1
		`, event.ChatID, createdAt)
	}
	s.appendSyncEventFromChat(event)
	return model.ChatEvent{
		ID:        id,
		ChatID:    event.ChatID,
		EventType: event.EventType,
		ActorID:   event.ActorID,
		EntityID:  event.EntityID,
		Metadata:  event.Metadata,
		CreatedAt: createdAt,
	}, nil
}

func (s *PostgresStore) ListChatEvents(userID string, since time.Time, limit int) ([]model.ChatEvent, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := s.pool.Query(context.Background(), `
		SELECT e.id, COALESCE(e.chat_id::text, ''), e.event_type,
		       COALESCE(e.actor_id::text, ''), COALESCE(e.entity_id, ''), e.metadata, e.created_at
		FROM chat_events e
		WHERE e.created_at > $1
		  AND (
		    e.chat_id IS NULL
		    OR EXISTS (
		      SELECT 1 FROM chat_members m
		      WHERE m.chat_id = e.chat_id AND m.user_id = $2
		    )
		  )
		ORDER BY e.created_at ASC
		LIMIT $3
	`, since, userID, limit+1)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	events := make([]model.ChatEvent, 0)
	for rows.Next() {
		var event model.ChatEvent
		var meta []byte
		if err := rows.Scan(
			&event.ID, &event.ChatID, &event.EventType,
			&event.ActorID, &event.EntityID, &meta, &event.CreatedAt,
		); err != nil {
			return nil, err
		}
		_ = json.Unmarshal(meta, &event.Metadata)
		events = append(events, event)
	}
	return events, rows.Err()
}

func (s *PostgresStore) ListChatsUpdatedSince(userID string, since time.Time) ([]model.Chat, error) {
	rows, err := s.pool.Query(context.Background(), `
		SELECT c.id, c.title, c.chat_type, c.dm_key, c.last_message, c.last_message_at, c.created_at, c.avatar_url
		FROM chats c
		JOIN chat_members m ON m.chat_id = c.id
		WHERE m.user_id = $1 AND c.updated_at > $2
		ORDER BY c.updated_at DESC
	`, userID, since)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	chats := make([]model.Chat, 0)
	for rows.Next() {
		chat, err := scanChatFromRows(rows)
		if err != nil {
			return nil, err
		}
		chat.MemberIDs, err = s.listChatMemberIDs(chat.ID)
		if err != nil {
			return nil, err
		}
		chats = append(chats, s.personalizeDM(chat, userID))
	}
	return chats, rows.Err()
}

func (s *PostgresStore) SoftDeleteMessage(chatID, messageID, userID string) (time.Time, error) {
	if !s.isChatMember(chatID, userID) {
		return time.Time{}, ErrForbidden
	}

	var exists bool
	err := s.pool.QueryRow(context.Background(), `
		SELECT EXISTS(
			SELECT 1 FROM messages
			WHERE id = $1 AND chat_id = $2 AND deleted_at IS NULL
		)
	`, messageID, chatID).Scan(&exists)
	if err != nil {
		return time.Time{}, err
	}
	if !exists {
		return time.Time{}, ErrNotFound
	}

	deletedAt := NowUTC()
	tag, err := s.pool.Exec(context.Background(), `
		UPDATE messages SET deleted_at = $3
		WHERE id = $1 AND chat_id = $2 AND deleted_at IS NULL
	`, messageID, chatID, deletedAt)
	if err != nil {
		return time.Time{}, err
	}
	if tag.RowsAffected() == 0 {
		return time.Time{}, ErrNotFound
	}
	_, _ = s.pool.Exec(context.Background(), `
		UPDATE chats SET updated_at = $2 WHERE id = $1
	`, chatID, deletedAt)
	return deletedAt, nil
}

func (s *PostgresStore) RecordEnvelopeDelivery(envelopeID, recipientAccountID, mailboxToken string, sizeBucket int) error {
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO envelope_delivery (
			envelope_id, recipient_account_id, mailbox_token, state, size_bucket
		) VALUES ($1, $2, $3, $4, $5)
		ON CONFLICT (envelope_id) DO NOTHING
	`, envelopeID, recipientAccountID, mailboxToken, model.DeliveryQueued, sizeBucket)
	return err
}

func (s *PostgresStore) MarkEnvelopesFetched(envelopeIDs []string) error {
	if len(envelopeIDs) == 0 {
		return nil
	}
	_, err := s.pool.Exec(context.Background(), `
		UPDATE envelope_delivery
		SET state = $2, fetched_at = NOW()
		WHERE envelope_id = ANY($1) AND state = $3
	`, envelopeIDs, model.DeliveryFetched, model.DeliveryQueued)
	return err
}

func (s *PostgresStore) MarkEnvelopesAcked(envelopeIDs []string) error {
	if len(envelopeIDs) == 0 {
		return nil
	}
	_, err := s.pool.Exec(context.Background(), `
		UPDATE envelope_delivery
		SET state = $2, acked_at = NOW()
		WHERE envelope_id = ANY($1)
	`, envelopeIDs, model.DeliveryAcked)
	return err
}

func (s *PostgresStore) IncrementRelayEnvelopeCount(userID string, count int) error {
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO messaging_limits (user_id, relay_envelopes_24h, relay_window_start)
		VALUES ($1, $2, NOW())
		ON CONFLICT (user_id) DO UPDATE SET
			relay_envelopes_24h = CASE
				WHEN messaging_limits.relay_window_start < NOW() - INTERVAL '24 hours'
				THEN $2
				ELSE messaging_limits.relay_envelopes_24h + $2
			END,
			relay_window_start = CASE
				WHEN messaging_limits.relay_window_start < NOW() - INTERVAL '24 hours'
				THEN NOW()
				ELSE messaging_limits.relay_window_start
			END,
			updated_at = NOW()
	`, userID, count)
	return err
}

func (s *PostgresStore) RelayEnvelopeCount24h(userID string) (int, error) {
	var count int
	err := s.pool.QueryRow(context.Background(), `
		SELECT relay_envelopes_24h FROM messaging_limits
		WHERE user_id = $1 AND relay_window_start >= NOW() - INTERVAL '24 hours'
	`, userID).Scan(&count)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, nil
	}
	return count, err
}

func (s *PostgresStore) GetUserPresence(userID string) (model.PresenceResponse, error) {
	var resp model.PresenceResponse
	resp.UserID = userID
	resp.ShowLastSeen = true
	err := s.pool.QueryRow(context.Background(), `
		SELECT status, last_seen_at, show_last_seen
		FROM user_presence WHERE user_id = $1
	`, userID).Scan(&resp.Status, &resp.LastSeenAt, &resp.ShowLastSeen)
	if errors.Is(err, pgx.ErrNoRows) {
		resp.Status = model.PresenceOffline
		return resp, nil
	}
	return resp, err
}

func (s *PostgresStore) SetUserPresence(userID, status string, showLastSeen *bool) error {
	show := true
	if showLastSeen != nil {
		show = *showLastSeen
	}
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO user_presence (user_id, status, last_seen_at, show_last_seen, updated_at)
		VALUES ($1, $2, CASE WHEN $2 = 'offline' THEN NOW() ELSE NULL END, $3, NOW())
		ON CONFLICT (user_id) DO UPDATE SET
			status = EXCLUDED.status,
			last_seen_at = CASE WHEN EXCLUDED.status = 'offline' THEN NOW() ELSE user_presence.last_seen_at END,
			show_last_seen = EXCLUDED.show_last_seen,
			updated_at = NOW()
	`, userID, status, show)
	return err
}

func (s *PostgresStore) PurgeExpiredQueue() (int, error) {
	// Mark delivery rows before deleting ciphertext so we do not leave "queued"
	// forever when the envelope body is gone (multi-device revoke / TTL).
	_, _ = s.pool.Exec(context.Background(), `
		UPDATE envelope_delivery ed
		SET state = $1, acked_at = COALESCE(ed.acked_at, NOW())
		FROM messages_queue mq
		WHERE mq.expires_at < NOW()
		  AND ed.envelope_id = mq.envelope_id
		  AND ed.state IN ($2, $3)
	`, model.DeliveryAcked, model.DeliveryQueued, model.DeliveryFetched)

	tag, err := s.pool.Exec(context.Background(), `
		DELETE FROM messages_queue WHERE expires_at < NOW()
	`)
	if err != nil {
		return 0, err
	}
	return int(tag.RowsAffected()), nil
}
