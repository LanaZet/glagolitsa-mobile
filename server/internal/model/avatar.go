// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package model

import (
	"encoding/base64"
	"errors"
	"strings"
)

const MaxAvatarURLChars = 300000

func NormalizeAvatarURL(value string) (string, error) {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return "", nil
	}
	if !strings.HasPrefix(trimmed, "data:image/") {
		return "", errors.New("avatar_url must be a data:image URI or empty")
	}
	if len(trimmed) > MaxAvatarURLChars {
		return "", errors.New("avatar is too large")
	}
	mediaType, payload, ok := strings.Cut(trimmed, ",")
	if !ok || !strings.HasSuffix(mediaType, ";base64") || payload == "" {
		return "", errors.New("avatar_url must be a base64 data:image URI")
	}
	mimeType := strings.TrimPrefix(mediaType, "data:")
	mimeType = strings.TrimSuffix(mimeType, ";base64")
	switch mimeType {
	case "image/png", "image/jpeg", "image/webp":
	default:
		return "", errors.New("avatar_url image type must be png, jpeg, or webp")
	}
	if _, err := base64.StdEncoding.DecodeString(payload); err != nil {
		return "", errors.New("avatar_url base64 payload is invalid")
	}
	return trimmed, nil
}
