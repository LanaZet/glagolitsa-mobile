// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package sync

import (
	"os"
	"strconv"
	"strings"
)

type Config struct {
	DefaultEventLimit int
	MaxEventLimit     int
	RetryIntervalSec  int
	RetryMaxAttempts  int
	SnapshotMinEvents int
}

func ConfigFromEnv() Config {
	return Config{
		DefaultEventLimit: envInt("SYNC_EVENT_LIMIT", 100),
		MaxEventLimit:     envInt("SYNC_EVENT_LIMIT_MAX", 500),
		RetryIntervalSec:  envInt("SYNC_RETRY_INTERVAL_SEC", 15),
		RetryMaxAttempts:  envInt("SYNC_RETRY_MAX_ATTEMPTS", 12),
		SnapshotMinEvents: envInt("SYNC_SNAPSHOT_MIN_EVENTS", 5000),
	}
}

func envInt(key string, fallback int) int {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback
	}
	value, err := strconv.Atoi(raw)
	if err != nil {
		return fallback
	}
	return value
}