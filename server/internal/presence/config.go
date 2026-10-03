// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package presence

import (
	"os"
	"strconv"
	"time"
)

type Config struct {
	OnlineTTL     time.Duration
	TypingTTL     time.Duration
	RecordingTTL  time.Duration
	CallTTL       time.Duration

	HeartbeatMinInterval time.Duration
	TypingMinInterval    time.Duration
	RecordingMinInterval time.Duration
	MaxUsersPerQuery     int
}

func ConfigFromEnv() Config {
	return Config{
		OnlineTTL:            envDurationSec("PRESENCE_ONLINE_TTL_SEC", 30),
		TypingTTL:            envDurationSec("PRESENCE_TYPING_TTL_SEC", 5),
		RecordingTTL:         envDurationSec("PRESENCE_RECORDING_TTL_SEC", 10),
		CallTTL:              envDurationSec("PRESENCE_CALL_TTL_SEC", 300),
		HeartbeatMinInterval: envDurationSec("PRESENCE_HEARTBEAT_MIN_SEC", 10),
		TypingMinInterval:    envDurationSec("PRESENCE_TYPING_MIN_SEC", 3),
		RecordingMinInterval: envDurationSec("PRESENCE_RECORDING_MIN_SEC", 3),
		MaxUsersPerQuery:     envInt("PRESENCE_MAX_USERS_QUERY", 50),
	}
}

func envDurationSec(key string, fallbackSec int) time.Duration {
	if raw := os.Getenv(key); raw != "" {
		if sec, err := strconv.Atoi(raw); err == nil && sec > 0 {
			return time.Duration(sec) * time.Second
		}
	}
	return time.Duration(fallbackSec) * time.Second
}

func envInt(key string, fallback int) int {
	if raw := os.Getenv(key); raw != "" {
		if v, err := strconv.Atoi(raw); err == nil && v > 0 {
			return v
		}
	}
	return fallback
}