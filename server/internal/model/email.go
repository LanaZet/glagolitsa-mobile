// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package model

import (
	"net/mail"
	"strings"
)

// Email rules — Mattermost public/model/utils.go (IsValidEmail, NormalizeEmail).

func NormalizeEmail(email string) string {
	return strings.ToLower(strings.TrimSpace(email))
}

func IsValidEmail(input string) bool {
	if input != strings.ToLower(input) {
		return false
	}
	addr, err := mail.ParseAddress(input)
	if err != nil || addr.Address != input {
		return false
	}
	if strings.Count(input, "@") > 1 {
		return false
	}
	return true
}