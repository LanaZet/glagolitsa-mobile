// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"context"

	"github.com/jackc/pgx/v5"

	"glagolitsa/server/internal/model"
)

func (s *PostgresStore) FindMessage(chatID, messageID string) (model.Message, error) {
	row := s.pool.QueryRow(context.Background(), `
		SELECT id, chat_id, sender_id, body, pending_id, created_at,
		       envelope_type, ciphertext, sender_device_id
		FROM messages
		WHERE id = $1 AND deleted_at IS NULL
		  AND ($2 = '' OR chat_id = $2::uuid)
	`, messageID, chatID)
	var message model.Message
	var pendingID *string
	var body *string
	var envelopeType *int
	var ciphertext []byte
	var senderDeviceID *string
	err := row.Scan(
		&message.ID, &message.ChatID, &message.SenderID, &body, &pendingID, &message.CreatedAt,
		&envelopeType, &ciphertext, &senderDeviceID,
	)
	if err != nil {
		if err == pgx.ErrNoRows {
			return model.Message{}, ErrNotFound
		}
		return model.Message{}, err
	}
	if body != nil {
		message.Body = *body
	}
	if pendingID != nil {
		message.PendingID = *pendingID
	}
	message.EnvelopeType = envelopeType
	if len(ciphertext) > 0 {
		message.Ciphertext = encodeMessageCiphertext(ciphertext)
	}
	if senderDeviceID != nil {
		message.SenderDeviceID = *senderDeviceID
	}
	return message, nil
}

func (s *PostgresStore) SetMessageReaction(messageID, userID, emoji string) ([]model.ReactionSummary, error) {
	_, err := s.pool.Exec(context.Background(), `
		INSERT INTO message_reactions (message_id, user_id, emoji)
		VALUES ($1, $2, $3)
		ON CONFLICT (message_id, user_id) DO UPDATE SET emoji = EXCLUDED.emoji, created_at = NOW()
	`, messageID, userID, emoji)
	if err != nil {
		return nil, err
	}
	return s.ListMessageReactions(messageID, userID)
}

func (s *PostgresStore) ClearMessageReaction(messageID, userID string) ([]model.ReactionSummary, error) {
	_, err := s.pool.Exec(context.Background(), `
		DELETE FROM message_reactions WHERE message_id = $1 AND user_id = $2
	`, messageID, userID)
	if err != nil {
		return nil, err
	}
	return s.ListMessageReactions(messageID, userID)
}

func (s *PostgresStore) ListMessageReactions(messageID, viewerID string) ([]model.ReactionSummary, error) {
	rows, err := s.pool.Query(context.Background(), `
		SELECT message_id, user_id, emoji FROM message_reactions WHERE message_id = $1
	`, messageID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	list := make([]MessageReaction, 0)
	for rows.Next() {
		var r MessageReaction
		if err := rows.Scan(&r.MessageID, &r.UserID, &r.Emoji); err != nil {
			return nil, err
		}
		list = append(list, r)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return SummarizeReactions(list, viewerID), nil
}
