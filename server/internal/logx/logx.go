// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package logx

import (
	"log/slog"
	"os"
	"strings"
)

func Init() *slog.Logger {
	level := slog.LevelInfo
	switch strings.ToLower(os.Getenv("LOG_LEVEL")) {
	case "debug":
		level = slog.LevelDebug
	case "warn", "warning":
		level = slog.LevelWarn
	case "error":
		level = slog.LevelError
	}
	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level})
	logger := slog.New(handler)
	slog.SetDefault(logger)
	return logger
}

func RedactedBytes(field string, size int) slog.Attr {
	return slog.Int(field+"_bytes", size)
}

func RedactedField(field string) slog.Attr {
	return slog.String(field, "[redacted]")
}