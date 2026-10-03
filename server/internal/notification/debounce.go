// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

// Debouncer — Mattermost multi-node safe coalesce for message wakes.
// Returns true if this wake should be sent (acquired slot), false if skipped.
// Never stores message content — keys are opaque user/device/type only.
type Debouncer interface {
	Allow(ctx context.Context, userID, deviceID, notifType string, window time.Duration) bool
}

// MemoryDebouncer — single-node (default).
type MemoryDebouncer struct {
	// filled by Service.lastWake under Service.lastWakeMu
}

// RedisDebouncer — cluster-wide SET NX EX (Mattermost HA pattern).
type RedisDebouncer struct {
	client *redis.Client
}

func NewRedisDebouncer(redisURL string) (*RedisDebouncer, error) {
	opts, err := redis.ParseURL(redisURL)
	if err != nil {
		return nil, err
	}
	client := redis.NewClient(opts)
	if err := client.Ping(context.Background()).Err(); err != nil {
		_ = client.Close()
		return nil, err
	}
	return &RedisDebouncer{client: client}, nil
}

func (d *RedisDebouncer) Allow(ctx context.Context, userID, deviceID, notifType string, window time.Duration) bool {
	if d == nil || d.client == nil || window <= 0 {
		return true
	}
	if notifType != TypeNewMessage && notifType != TypeMessageSync {
		return true
	}
	key := fmt.Sprintf("glag:push:debounce:%s:%s:%s", userID, deviceID, notifType)
	// SET NX EX — first node wins; others skip (no content in key/value).
	ok, err := d.client.SetNX(ctx, key, "1", window).Result()
	if err != nil {
		// Fail open: better duplicate wake than silent drop.
		return true
	}
	return ok
}

func (d *RedisDebouncer) Close() error {
	if d == nil || d.client == nil {
		return nil
	}
	return d.client.Close()
}
