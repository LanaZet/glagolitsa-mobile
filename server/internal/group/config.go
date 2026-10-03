// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package group

import (
	"os"
	"strconv"
	"strings"
)

type Config struct {
	MaxGroupsCreated24h int
	DefaultInviteHours  int
}

func ConfigFromEnv() Config {
	return Config{
		MaxGroupsCreated24h: envInt("GROUP_MAX_CREATED_24H", 20),
		DefaultInviteHours:  envInt("GROUP_INVITE_DEFAULT_HOURS", 168),
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