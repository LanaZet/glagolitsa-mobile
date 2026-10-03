// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package model

import (
	"regexp"
	"strings"
)

// Username rules — Mattermost public/model/user.go (IsValidUsername, NormalizeUsername).

const (
	UsernameMinLength = 1
	UsernameMaxLength = 64
)

var validUsernameChars = regexp.MustCompile(`^[a-z0-9\.\-_]+$`)
var validRegistrationUsernameChars = regexp.MustCompile(`^[a-z]+$`)

var restrictedUsernames = map[string]struct{}{
	"all":        {},
	"channel":    {},
	"matterbot":  {},
	"system":     {},
	"admin":      {},
	"api":        {},
	"login":      {},
	"glagolitsa": {},
	"ghost":      {},
	"explore":    {},
}

func NormalizeUsername(username string) string {
	return strings.ToLower(strings.TrimSpace(username))
}

func IsValidUsername(s string) bool {
	return isValidUsername(s, validUsernameChars)
}

func IsValidRegistrationUsername(s string) bool {
	return isValidUsername(s, validRegistrationUsernameChars)
}

func isValidUsername(s string, pattern *regexp.Regexp) bool {
	if len(s) < UsernameMinLength || len(s) > UsernameMaxLength {
		return false
	}
	if !pattern.MatchString(s) {
		return false
	}
	_, found := restrictedUsernames[s]
	return !found
}
