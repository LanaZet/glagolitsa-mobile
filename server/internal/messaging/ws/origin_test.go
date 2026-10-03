// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package ws

import (
	"net/http/httptest"
	"testing"
)

func TestOriginPolicy_allowsEmptyOriginInProduction(t *testing.T) {
	policy := OriginPolicy{
		AllowedOrigins:   map[string]struct{}{"https://app.example.com": {}},
		AllowEmptyOrigin: true,
	}
	req := httptest.NewRequest("GET", "/api/ws", nil)
	if !policy.allows(req) {
		t.Fatal("native clients without Origin should be allowed")
	}
}

func TestOriginPolicy_rejectsUnknownOriginInProduction(t *testing.T) {
	policy := OriginPolicy{
		AllowedOrigins:   map[string]struct{}{"https://app.example.com": {}},
		AllowEmptyOrigin: true,
	}
	req := httptest.NewRequest("GET", "/api/ws", nil)
	req.Header.Set("Origin", "https://evil.example.com")
	if policy.allows(req) {
		t.Fatal("unexpected origin should be rejected")
	}
}