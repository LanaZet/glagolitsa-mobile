// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"

	"glagolitsa/server/internal/model"
)

func (s *PostgresStore) GetPresencePrivacy(userID string) (model.PresencePrivacySettings, error) {
	var settings model.PresencePrivacySettings
	err := s.pool.QueryRow(context.Background(), `
		SELECT online_visibility, last_seen_visibility
		FROM presence_privacy WHERE user_id = $1
	`, userID).Scan(&settings.OnlineVisibility, &settings.LastSeenVisibility)
	if errors.Is(err, pgx.ErrNoRows) {
		return model.PresencePrivacySettings{
			OnlineVisibility:   model.PresenceVisibilityContacts,
			LastSeenVisibility: model.PresenceVisibilityContacts,
		}, nil
	}
	return settings, err
}

func (s *PostgresStore) SetPresencePrivacy(userID string, settings model.PresencePrivacySettings) error {
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO presence_privacy (user_id, online_visibility, last_seen_visibility, updated_at)
		VALUES ($1, $2, $3, NOW())
		ON CONFLICT (user_id) DO UPDATE SET
			online_visibility = EXCLUDED.online_visibility,
			last_seen_visibility = EXCLUDED.last_seen_visibility,
			updated_at = NOW()
	`, userID, settings.OnlineVisibility, settings.LastSeenVisibility)
	return err
}

func (s *PostgresStore) GetLastSeenAt(userID string) (*time.Time, error) {
	var at *time.Time
	err := s.pool.QueryRow(context.Background(), `
		SELECT last_seen_at FROM user_presence WHERE user_id = $1
	`, userID).Scan(&at)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	return at, err
}

func (s *PostgresStore) SetLastSeenAt(userID string, at time.Time) error {
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO user_presence (user_id, status, last_seen_at, show_last_seen, updated_at)
		VALUES ($1, 'offline', $2, TRUE, NOW())
		ON CONFLICT (user_id) DO UPDATE SET
			status = 'offline',
			last_seen_at = $2,
			updated_at = NOW()
	`, userID, at)
	return err
}

func (s *PostgresStore) ListContactUserIDs(userID string) ([]string, error) {
	rows, err := s.pool.Query(context.Background(), `
		SELECT DISTINCT cm2.user_id
		FROM chat_members cm1
		JOIN chats c ON c.id = cm1.chat_id AND c.chat_type = 'dm'
		JOIN chat_members cm2 ON cm2.chat_id = c.id AND cm2.user_id <> $1
		WHERE cm1.user_id = $1
	`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	ids := make([]string, 0)
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

func (s *PostgresStore) GetChatMemberIDs(chatID, viewerID string) ([]string, error) {
	chat, err := s.GetChat(chatID, viewerID)
	if err != nil {
		return nil, err
	}
	return chat.MemberIDs, nil
}
