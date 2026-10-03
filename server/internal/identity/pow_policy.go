// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"os"
	"strconv"
)

const defaultRegistrationPowDifficulty = 14

// PowRequired — PoW обязателен по умолчанию; отключить: REGISTRATION_POW_REQUIRED=false (dev/tests).
func PowRequired() bool {
	switch os.Getenv("REGISTRATION_POW_REQUIRED") {
	case "0", "false", "FALSE", "no":
		return false
	default:
		return true
	}
}

// registrationPowDifficulty — leading zero bits для PoW (8–20). 14 ≈ быстрый вход на мобиле, 16 — жёстче.
func registrationPowDifficulty() int {
	raw := os.Getenv("REGISTRATION_POW_DIFFICULTY")
	if raw == "" {
		return defaultRegistrationPowDifficulty
	}
	n, err := strconv.Atoi(raw)
	if err != nil || n < 8 || n > 20 {
		return defaultRegistrationPowDifficulty
	}
	return n
}
