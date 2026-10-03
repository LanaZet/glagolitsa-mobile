// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package media

import (
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	DefaultTTL     time.Duration
	RetentionEvery time.Duration
	RetentionBatch int
	CDNBaseURL     string
	ScanEnabled    bool
	MaxBytesByKind map[string]int64
	StoragePrefix  string
	UserQuotaBytes int64
	ChatQuotaBytes int64
	ChunkMaxBytes  int64
}

func ConfigFromEnv() Config {
	cfg := Config{
		DefaultTTL:     30 * 24 * time.Hour,
		RetentionEvery: 15 * time.Minute,
		RetentionBatch: 200,
		CDNBaseURL:     strings.TrimRight(strings.TrimSpace(os.Getenv("MEDIA_CDN_BASE_URL")), "/"),
		ScanEnabled:    strings.EqualFold(os.Getenv("MEDIA_SCAN_ENABLED"), "true"),
		StoragePrefix:  envOrDefault("MEDIA_STORAGE_PREFIX", "media"),
		UserQuotaBytes: envInt64("MEDIA_USER_QUOTA_BYTES", 2<<30), // 2 GiB per user
		ChatQuotaBytes: envInt64("MEDIA_CHAT_QUOTA_BYTES", 8<<30), // 8 GiB per chat
		ChunkMaxBytes:  envInt64("MEDIA_CHUNK_MAX_BYTES", 2<<20),  // 2 MiB
		MaxBytesByKind: map[string]int64{
			KindAvatar:    2 << 20,
			KindPhoto:     20 << 20,
			KindVideo:     100 << 20,
			KindDocument:  50 << 20,
			KindVoice:     10 << 20,
			KindAudio:     50 << 20,
			KindThumbnail: 512 << 10,
			KindSticker:   1 << 20,
		},
	}
	if days := envInt("MEDIA_TTL_DAYS", 30); days > 0 {
		cfg.DefaultTTL = time.Duration(days) * 24 * time.Hour
	}
	if every := envInt("MEDIA_RETENTION_INTERVAL_SEC", 900); every > 0 {
		cfg.RetentionEvery = time.Duration(every) * time.Second
	}
	if batch := envInt("MEDIA_RETENTION_BATCH", 200); batch > 0 {
		cfg.RetentionBatch = batch
	}
	overrideLimit(KindAvatar, "MEDIA_MAX_AVATAR_BYTES", cfg.MaxBytesByKind)
	overrideLimit(KindPhoto, "MEDIA_MAX_PHOTO_BYTES", cfg.MaxBytesByKind)
	overrideLimit(KindVideo, "MEDIA_MAX_VIDEO_BYTES", cfg.MaxBytesByKind)
	overrideLimit(KindDocument, "MEDIA_MAX_DOCUMENT_BYTES", cfg.MaxBytesByKind)
	overrideLimit(KindVoice, "MEDIA_MAX_VOICE_BYTES", cfg.MaxBytesByKind)
	overrideLimit(KindAudio, "MEDIA_MAX_AUDIO_BYTES", cfg.MaxBytesByKind)
	overrideLimit(KindThumbnail, "MEDIA_MAX_THUMBNAIL_BYTES", cfg.MaxBytesByKind)
	overrideLimit(KindSticker, "MEDIA_MAX_STICKER_BYTES", cfg.MaxBytesByKind)
	if cfg.ChunkMaxBytes <= 0 {
		cfg.ChunkMaxBytes = 2 << 20
	}
	return cfg
}

func (c Config) MaxBytes(kind string) int64 {
	if limit, ok := c.MaxBytesByKind[kind]; ok && limit > 0 {
		return limit
	}
	return c.MaxBytesByKind[KindDocument]
}

func overrideLimit(kind, envKey string, limits map[string]int64) {
	if value := envInt64(envKey, 0); value > 0 {
		limits[kind] = value
	}
}

func envOrDefault(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
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

func envInt64(key string, fallback int64) int64 {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	if err != nil {
		return fallback
	}
	return value
}
