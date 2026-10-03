// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"strings"
	"time"
	"unicode"
)

const (
	RecoveryKeyMinLength   = 16
	RecoveryTicketTTL      = 10 * time.Minute
	TrustedRecoveryTTL     = 10 * time.Minute
	recoveryTicketHashPref = "glagolitsa-recovery-ticket:"
)

// DummyRecoveryKeyHash is compared when the account or key is missing so verify
// does not exit faster than a real check (OWASP timing / enumeration).
var DummyRecoveryKeyHash = HashRecoveryKey("glagolitsa-dummy-recovery-key-not-used")

func NormalizeRecoveryKey(key string) string {
	var b strings.Builder
	b.Grow(len(key))
	for _, r := range key {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(unicode.ToLower(r))
		}
	}
	return b.String()
}

func RecoveryKeyHint(normalized string) string {
	if len(normalized) < 4 {
		return ""
	}
	return normalized[len(normalized)-4:]
}

func HashRecoveryKey(key string) string {
	normalized := NormalizeRecoveryKey(key)
	sum := sha256.Sum256([]byte("glagolitsa-recovery:" + normalized))
	return hex.EncodeToString(sum[:])
}

func NewRecoveryToken() (raw string, hash string, err error) {
	buf := make([]byte, 32)
	if _, err = rand.Read(buf); err != nil {
		return "", "", fmt.Errorf("recovery token entropy: %w", err)
	}
	raw = base64.RawURLEncoding.EncodeToString(buf)
	return raw, HashRecoveryToken(raw), nil
}

func HashRecoveryToken(raw string) string {
	sum := sha256.Sum256([]byte(recoveryTicketHashPref + strings.TrimSpace(raw)))
	return hex.EncodeToString(sum[:])
}
