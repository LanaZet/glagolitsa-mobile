// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package media

import (
	"fmt"
	"strings"
)

// PrivacyGuard — Media Service never inspects decrypted content.
type PrivacyGuard struct{}

func (PrivacyGuard) ValidateClientMimeType(mimeType string) error {
	mimeType = strings.ToLower(strings.TrimSpace(mimeType))
	if mimeType == "" {
		return nil
	}
	if len(mimeType) > 128 {
		return fmt.Errorf("mime_type is too long")
	}
	return nil
}

func (PrivacyGuard) ValidateEncryptedHash(hash string) error {
	hash = strings.TrimSpace(hash)
	if hash == "" {
		return nil
	}
	if len(hash) > 128 {
		return fmt.Errorf("content hash is too long")
	}
	return nil
}