// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package media

import (
	"fmt"
	"strings"
)

// Open photo limits (architecture: channel creative path).
const (
	OpenPhotoMaxBytes int64 = 15 << 20 // 15 MiB
)

var openPhotoMIMEs = map[string]struct{}{
	"image/jpeg": {},
	"image/png":  {},
	"image/webp": {},
}

func isAllowedOpenPhotoMIME(mime string) bool {
	_, ok := openPhotoMIMEs[strings.ToLower(strings.TrimSpace(mime))]
	return ok
}

// sniffOpenImageMIME detects jpeg/png/webp from magic bytes.
// Returns empty string if not a supported open photo.
func sniffOpenImageMIME(head []byte) string {
	if len(head) >= 3 && head[0] == 0xFF && head[1] == 0xD8 && head[2] == 0xFF {
		return "image/jpeg"
	}
	if len(head) >= 8 &&
		head[0] == 0x89 && head[1] == 0x50 && head[2] == 0x4E && head[3] == 0x47 &&
		head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A {
		return "image/png"
	}
	// RIFF....WEBP
	if len(head) >= 12 &&
		string(head[0:4]) == "RIFF" &&
		string(head[8:12]) == "WEBP" {
		return "image/webp"
	}
	return ""
}

func validateOpenPhotoClientMIME(mime string) error {
	mime = strings.ToLower(strings.TrimSpace(mime))
	if mime == "" {
		return nil // may be sniffed later
	}
	if !isAllowedOpenPhotoMIME(mime) {
		return fmt.Errorf("open photo mime must be image/jpeg, image/png, or image/webp")
	}
	return nil
}
