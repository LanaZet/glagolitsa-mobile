// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package presence

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

type RedisStore struct {
	client *redis.Client
	cfg    Config
}

func NewRedisStore(redisURL string, cfg Config) (*RedisStore, error) {
	opts, err := redis.ParseURL(redisURL)
	if err != nil {
		return nil, fmt.Errorf("parse redis url: %w", err)
	}
	client := redis.NewClient(opts)
	if err := client.Ping(context.Background()).Err(); err != nil {
		return nil, fmt.Errorf("ping redis: %w", err)
	}
	return &RedisStore{client: client, cfg: cfg}, nil
}

func (s *RedisStore) Close() error {
	return s.client.Close()
}

func userKey(userID string) string       { return "presence:user:" + userID }
func deviceKey(userID, deviceID string) string {
	return "presence:device:" + userID + ":" + deviceID
}
func typingKey(chatID, userID string) string {
	return "presence:typing:" + chatID + ":" + userID
}
func recordingKey(chatID, userID string) string {
	return "presence:recording:" + chatID + ":" + userID
}
func callKey(userID string) string { return "presence:call:" + userID }

func (s *RedisStore) SetUserOnline(userID, deviceID string, ttl time.Duration) (bool, error) {
	ctx := context.Background()
	wasOffline := false
	prev, err := s.client.Get(ctx, userKey(userID)).Result()
	if err == redis.Nil || prev == "" || prev == "offline" {
		wasOffline = true
	}
	pipe := s.client.Pipeline()
	pipe.Set(ctx, userKey(userID), "online", ttl)
	if deviceID != "" {
		pipe.Set(ctx, deviceKey(userID, deviceID), "1", ttl)
	}
	_, err = pipe.Exec(ctx)
	return wasOffline, err
}

func (s *RedisStore) TouchDevice(userID, deviceID string, ttl time.Duration) error {
	_, err := s.SetUserOnline(userID, deviceID, ttl)
	return err
}

func (s *RedisStore) RemoveDevice(userID, deviceID string) (int, error) {
	ctx := context.Background()
	if deviceID != "" {
		_ = s.client.Del(ctx, deviceKey(userID, deviceID)).Err()
	}
	pattern := "presence:device:" + userID + ":*"
	iter := s.client.Scan(ctx, 0, pattern, 100).Iterator()
	left := 0
	for iter.Next(ctx) {
		left++
	}
	if left == 0 {
		callID, inCall, _ := s.GetInCall(userID)
		if inCall {
			_ = s.client.Set(ctx, userKey(userID), "in_call", s.cfg.CallTTL).Err()
			_ = callID
		} else {
			_ = s.client.Del(ctx, userKey(userID)).Err()
		}
	}
	return left, iter.Err()
}

func (s *RedisStore) GetUserStatus(userID string) (string, error) {
	ctx := context.Background()
	if callID, ok, _ := s.GetInCall(userID); ok && callID != "" {
		return "in_call", nil
	}
	status, err := s.client.Get(ctx, userKey(userID)).Result()
	if err == redis.Nil {
		return "offline", nil
	}
	if status == "" {
		return "offline", nil
	}
	return status, err
}

func (s *RedisStore) ListActiveDevices(userID string) ([]string, error) {
	ctx := context.Background()
	pattern := "presence:device:" + userID + ":*"
	prefix := "presence:device:" + userID + ":"
	out := make([]string, 0)
	iter := s.client.Scan(ctx, 0, pattern, 100).Iterator()
	for iter.Next(ctx) {
		key := iter.Val()
		out = append(out, strings.TrimPrefix(key, prefix))
	}
	return out, iter.Err()
}

func (s *RedisStore) SetTyping(chatID, userID string, ttl time.Duration) error {
	return s.client.Set(context.Background(), typingKey(chatID, userID), "1", ttl).Err()
}

func (s *RedisStore) ClearTyping(chatID, userID string) error {
	return s.client.Del(context.Background(), typingKey(chatID, userID)).Err()
}

func (s *RedisStore) ListTyping(chatID string) ([]string, error) {
	ctx := context.Background()
	pattern := "presence:typing:" + chatID + ":*"
	prefix := "presence:typing:" + chatID + ":"
	out := make([]string, 0)
	iter := s.client.Scan(ctx, 0, pattern, 100).Iterator()
	for iter.Next(ctx) {
		out = append(out, strings.TrimPrefix(iter.Val(), prefix))
	}
	return out, iter.Err()
}

func (s *RedisStore) SetRecording(chatID, userID, kind string, ttl time.Duration) error {
	kind = strings.TrimSpace(kind)
	if kind == "" {
		kind = "voice"
	}
	return s.client.Set(context.Background(), recordingKey(chatID, userID), kind, ttl).Err()
}

func (s *RedisStore) ClearRecording(chatID, userID string) error {
	return s.client.Del(context.Background(), recordingKey(chatID, userID)).Err()
}

func (s *RedisStore) ListRecording(chatID string) (map[string]string, error) {
	ctx := context.Background()
	pattern := "presence:recording:" + chatID + ":*"
	prefix := "presence:recording:" + chatID + ":"
	out := make(map[string]string)
	iter := s.client.Scan(ctx, 0, pattern, 100).Iterator()
	for iter.Next(ctx) {
		key := iter.Val()
		userID := strings.TrimPrefix(key, prefix)
		kind, err := s.client.Get(ctx, key).Result()
		if err != nil {
			continue
		}
		out[userID] = kind
	}
	return out, iter.Err()
}

func (s *RedisStore) SetInCall(userID, callID string, ttl time.Duration) error {
	ctx := context.Background()
	pipe := s.client.Pipeline()
	pipe.Set(ctx, callKey(userID), callID, ttl)
	pipe.Set(ctx, userKey(userID), "in_call", ttl)
	_, err := pipe.Exec(ctx)
	return err
}

func (s *RedisStore) ClearInCall(userID string) error {
	ctx := context.Background()
	_ = s.client.Del(ctx, callKey(userID)).Err()
	devices, _ := s.ListActiveDevices(userID)
	if len(devices) > 0 {
		return s.client.Set(ctx, userKey(userID), "online", s.cfg.OnlineTTL).Err()
	}
	return s.client.Del(ctx, userKey(userID)).Err()
}

func (s *RedisStore) GetInCall(userID string) (string, bool, error) {
	callID, err := s.client.Get(context.Background(), callKey(userID)).Result()
	if err == redis.Nil {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	return callID, callID != "", nil
}