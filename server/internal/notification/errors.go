// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

import "strings"

// IsPermanentTokenError — do not retry; mark token invalid.
// Transient network/5xx errors should retry without invalidating.
func IsPermanentTokenError(err error) bool {
	if err == nil {
		return false
	}
	s := strings.ToLower(err.Error())
	permanent := []string{
		"unregistered",
		"notregistered",
		"invalid_argument",
		"registration-token-not-registered",
		"baddevicetoken",
		"unregistered",
		"apns permanent",
		"410",
		"endpoint must be https",
		"empty endpoint",
	}
	for _, p := range permanent {
		if strings.Contains(s, p) {
			return true
		}
	}
	return false
}
