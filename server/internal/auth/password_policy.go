// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package auth

import (
	"fmt"
	"strings"

	"glagolitsa/server/internal/model"
)

// Password rules — Mattermost channels/app/users/password.go.

const (
	PasswordMinimumLength = 5
	PasswordMaximumLength = 72
	DefaultPasswordMinLen = 8
)

type PasswordSettings struct {
	MinimumLength int
	RequireLower  bool
	RequireUpper  bool
	RequireNumber bool
	RequireSymbol bool
}

func DefaultPasswordSettings() PasswordSettings {
	return PasswordSettings{MinimumLength: DefaultPasswordMinLen}
}

func (s PasswordSettings) normalized() PasswordSettings {
	if s.MinimumLength == 0 {
		s.MinimumLength = DefaultPasswordMinLen
	}
	if s.MinimumLength < PasswordMinimumLength {
		s.MinimumLength = PasswordMinimumLength
	}
	if s.MinimumLength > PasswordMaximumLength {
		s.MinimumLength = PasswordMaximumLength
	}
	return s
}

func ValidatePassword(password string, settings PasswordSettings) error {
	settings = settings.normalized()

	if len(password) < settings.MinimumLength {
		return fmt.Errorf("password must be at least %d characters", settings.MinimumLength)
	}
	if len(password) > PasswordMaximumLength {
		return fmt.Errorf("password must be at most %d characters", PasswordMaximumLength)
	}

	if settings.RequireLower && !strings.ContainsAny(password, model.LowercaseLetters) {
		return fmt.Errorf("password must contain a lowercase letter")
	}
	if settings.RequireUpper && !strings.ContainsAny(password, model.UppercaseLetters) {
		return fmt.Errorf("password must contain an uppercase letter")
	}
	if settings.RequireNumber && !strings.ContainsAny(password, model.Numbers) {
		return fmt.Errorf("password must contain a number")
	}
	if settings.RequireSymbol && !strings.ContainsAny(password, model.Symbols) {
		return fmt.Errorf("password must contain a symbol")
	}
	return nil
}