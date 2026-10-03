// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package queue

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"

	"glagolitsa/server/internal/store"
)

type RedisRelayQueue struct {
	client *redis.Client
	ttl    time.Duration
}

type redisEnvelope struct {
	EnvelopeID   string    `json:"envelope_id"`
	MailboxToken string    `json:"mailbox_token"`
	EnvelopeType int       `json:"envelope_type"`
	Ciphertext   string    `json:"ciphertext"`
	SizeBucket   int       `json:"size_bucket"`
	CreatedAt    time.Time `json:"created_at"`
	ExpiresAt    time.Time `json:"expires_at"`
}

func NewRedisRelayQueue(redisURL string, ttl time.Duration) (*RedisRelayQueue, error) {
	opts, err := redis.ParseURL(redisURL)
	if err != nil {
		return nil, fmt.Errorf("parse redis url: %w", err)
	}
	client := redis.NewClient(opts)
	if err := client.Ping(context.Background()).Err(); err != nil {
		return nil, fmt.Errorf("ping redis: %w", err)
	}
	if ttl <= 0 {
		ttl = DefaultEnvelopeTTL
	}
	return &RedisRelayQueue{client: client, ttl: ttl}, nil
}

func (q *RedisRelayQueue) Close() error {
	return q.client.Close()
}

func (q *RedisRelayQueue) EnqueueRelayEnvelopes(envelopes []store.RelayEnvelopeInput) ([]string, error) {
	if len(envelopes) == 0 {
		return nil, errors.New("envelopes are required")
	}
	ctx := context.Background()
	ids := make([]string, 0, len(envelopes))
	pipe := q.client.Pipeline()
	for _, envelope := range envelopes {
		envelopeID := uuid.NewString()
		expiresAt := envelope.ExpiresAt
		if expiresAt.IsZero() {
			expiresAt = store.NowUTC().Add(q.ttl)
		}
		createdAt := store.NowUTC()
		payload := redisEnvelope{
			EnvelopeID:   envelopeID,
			MailboxToken: envelope.MailboxToken,
			EnvelopeType: envelope.EnvelopeType,
			Ciphertext:   base64.StdEncoding.EncodeToString(envelope.Ciphertext),
			SizeBucket:   envelope.SizeBucket,
			CreatedAt:    createdAt,
			ExpiresAt:    expiresAt,
		}
		raw, err := json.Marshal(payload)
		if err != nil {
			return nil, err
		}
		envKey := envelopeKey(envelopeID)
		mailboxKey := mailboxKey(envelope.MailboxToken)
		pipe.Set(ctx, envKey, raw, time.Until(expiresAt))
		pipe.ZAdd(ctx, mailboxKey, redis.Z{
			Score:  float64(createdAt.UnixNano()),
			Member: envelopeID,
		})
		pipe.Expire(ctx, mailboxKey, q.ttl)
		ids = append(ids, envelopeID)
	}
	if _, err := pipe.Exec(ctx); err != nil {
		return nil, err
	}
	return ids, nil
}

func (q *RedisRelayQueue) ListQueuedEnvelopes(mailboxTokens []string, limit int) ([]store.QueuedEnvelopeRecord, error) {
	if len(mailboxTokens) == 0 {
		return []store.QueuedEnvelopeRecord{}, nil
	}
	if limit <= 0 {
		limit = 50
	}
	ctx := context.Background()
	now := store.NowUTC()
	envelopes := make([]store.QueuedEnvelopeRecord, 0, limit)
	for _, mailboxToken := range mailboxTokens {
		ids, err := q.client.ZRange(ctx, mailboxKey(mailboxToken), 0, int64(limit*2)).Result()
		if err != nil {
			return nil, err
		}
		for _, envelopeID := range ids {
			record, err := q.loadEnvelope(ctx, envelopeID)
			if err != nil {
				if errors.Is(err, redis.Nil) {
					_ = q.client.ZRem(ctx, mailboxKey(mailboxToken), envelopeID).Err()
					continue
				}
				return nil, err
			}
			if !record.ExpiresAt.After(now) {
				_ = q.deleteEnvelope(ctx, mailboxToken, envelopeID)
				continue
			}
			envelopes = append(envelopes, record)
			if len(envelopes) >= limit {
				return envelopes, nil
			}
		}
	}
	return envelopes, nil
}

func (q *RedisRelayQueue) AckQueuedEnvelopes(mailboxTokens []string, envelopeIDs []string) (int, error) {
	if len(envelopeIDs) == 0 {
		return 0, nil
	}
	ctx := context.Background()
	deleted := 0
	for _, envelopeID := range envelopeIDs {
		record, err := q.loadEnvelope(ctx, envelopeID)
		if err != nil {
			if errors.Is(err, redis.Nil) {
				continue
			}
			return deleted, err
		}
		if !tokenAllowed(mailboxTokens, record.MailboxToken) {
			continue
		}
		if err := q.deleteEnvelope(ctx, record.MailboxToken, envelopeID); err != nil {
			return deleted, err
		}
		deleted++
	}
	return deleted, nil
}

func (q *RedisRelayQueue) loadEnvelope(ctx context.Context, envelopeID string) (store.QueuedEnvelopeRecord, error) {
	raw, err := q.client.Get(ctx, envelopeKey(envelopeID)).Bytes()
	if err != nil {
		return store.QueuedEnvelopeRecord{}, err
	}
	var payload redisEnvelope
	if err := json.Unmarshal(raw, &payload); err != nil {
		return store.QueuedEnvelopeRecord{}, err
	}
	ciphertext, err := base64.StdEncoding.DecodeString(payload.Ciphertext)
	if err != nil {
		return store.QueuedEnvelopeRecord{}, err
	}
	return store.QueuedEnvelopeRecord{
		EnvelopeID:   payload.EnvelopeID,
		MailboxToken: payload.MailboxToken,
		EnvelopeType: payload.EnvelopeType,
		Ciphertext:   ciphertext,
		SizeBucket:   payload.SizeBucket,
		CreatedAt:    payload.CreatedAt,
		ExpiresAt:    payload.ExpiresAt,
	}, nil
}

func (q *RedisRelayQueue) deleteEnvelope(ctx context.Context, mailboxToken, envelopeID string) error {
	pipe := q.client.Pipeline()
	pipe.Del(ctx, envelopeKey(envelopeID))
	pipe.ZRem(ctx, mailboxKey(mailboxToken), envelopeID)
	_, err := pipe.Exec(ctx)
	return err
}

func envelopeKey(envelopeID string) string {
	return "mq:env:" + envelopeID
}

func mailboxKey(mailboxToken string) string {
	return "mq:mailbox:" + mailboxToken
}

func tokenAllowed(tokens []string, candidate string) bool {
	for _, token := range tokens {
		if token == candidate {
			return true
		}
	}
	return false
}

func (q *RedisRelayQueue) Stats(ctx context.Context) (int64, error) {
	var cursor uint64
	var total int64
	for {
		keys, next, err := q.client.Scan(ctx, cursor, "mq:env:*", 100).Result()
		if err != nil {
			return total, err
		}
		total += int64(len(keys))
		cursor = next
		if cursor == 0 {
			break
		}
	}
	return total, nil
}

func (q *RedisRelayQueue) String() string {
	return "redis:" + strconv.Itoa(int(q.ttl.Hours())) + "h"
}