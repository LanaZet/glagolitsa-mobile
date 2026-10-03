// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package auth

import (
	"log/slog"
	"os"
)

// MustProductionSecret завершает процесс, если в production не задан JWT_SECRET.
func MustProductionSecret() {
	env := os.Getenv("ENV")
	if env == "" {
		env = os.Getenv("GO_ENV")
	}
	if env != "production" {
		return
	}
	if os.Getenv("JWT_SECRET") == "" {
		slog.Error("JWT_SECRET is required when ENV=production")
		os.Exit(1)
	}
}