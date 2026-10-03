// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package profile

import "strings"

const (
	autocompleteMinLen = 2
	autocompleteLimit  = 8
	searchLimit        = 20
)

func normalizeSearchQuery(q string) string {
	return strings.ToLower(strings.TrimSpace(q))
}