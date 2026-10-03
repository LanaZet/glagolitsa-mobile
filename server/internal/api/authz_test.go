// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func TestSocketAccessGate_blocksInactiveAccount(t *testing.T) {
	mem := store.NewMemory()
	gate := &socketAccessGate{store: mem}

	account, err := mem.CreateAccount(model.User{
		Username: "ws-user",
		Email:    "",
	}, mustHash(t, "password123"))
	if err != nil {
		t.Fatalf("create account: %v", err)
	}
	if err := mem.SetAccountStatus(account.ID, model.AccountStatusInactive); err != nil {
		t.Fatalf("set inactive: %v", err)
	}

	token, _, err := auth.IssueAccessToken(auth.AccessTokenInput{
		UserID:    account.ID,
		Username:  "ws-user",
		TrustTier: model.TrustTierNew,
		SessionID: "sess-1",
	})
	if err != nil {
		t.Fatalf("issue token: %v", err)
	}
	_ = mem.CreateSession(model.SessionRecord{
		ID:               "sess-1",
		UserID:           account.ID,
		RefreshTokenHash: []byte("hash"),
		ExpiresAt:        store.NowUTC().Add(auth.RefreshTokenTTL),
	})

	claims, err := auth.ParseToken(token)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if err := gate.AllowSocket(claims); err == nil {
		t.Fatal("expected inactive account to be blocked from websocket")
	}
}

func TestPendingDeviceRoutes_containsDeviceRegistration(t *testing.T) {
	if _, ok := pendingDeviceRoutes["POST /api/devices"]; !ok {
		t.Fatal("accounts without device must be able to register device")
	}
}

func TestPendingDeviceRoutes_excludesMessagingRelay(t *testing.T) {
	messagingRoutes := []string{
		"POST /api/messages/relay",
		"GET /api/messages/queue",
		"POST /api/messages/queue/ack",
		"GET /api/chats",
		"POST /api/chats/dm",
	}
	for _, route := range messagingRoutes {
		if _, ok := pendingDeviceRoutes[route]; ok {
			t.Fatalf("route %q must require device registration", route)
		}
	}
}

func TestRouteKey_matchesServeMuxPattern(t *testing.T) {
	mux := http.NewServeMux()
	var got string
	mux.HandleFunc("POST /api/devices", func(w http.ResponseWriter, r *http.Request) {
		got = routeKey(r)
	})
	req := httptest.NewRequest(http.MethodPost, "/api/devices", nil)
	mux.ServeHTTP(httptest.NewRecorder(), req)
	if got != "POST /api/devices" {
		t.Fatalf("routeKey() = %q, want POST /api/devices", got)
	}
	if _, ok := pendingDeviceRoutes[got]; !ok {
		t.Fatalf("pending device route map missing %q", got)
	}
}

func TestPendingDeviceRouteAllowed_selfDevicesOnly(t *testing.T) {
	mux := http.NewServeMux()
	var selfAllowed bool
	var peerAllowed bool
	mux.HandleFunc("GET /api/users/{id}/devices", func(w http.ResponseWriter, r *http.Request) {
		selfAllowed = pendingDeviceRouteAllowed(r, "user-self", routeKey(r))
		peerAllowed = pendingDeviceRouteAllowed(r, "user-other", routeKey(r))
	})

	req := httptest.NewRequest(http.MethodGet, "/api/users/user-self/devices", nil)
	mux.ServeHTTP(httptest.NewRecorder(), req)
	if !selfAllowed {
		t.Fatal("pending account must be allowed to inspect its own devices")
	}
	if peerAllowed {
		t.Fatal("pending account must not inspect another user's devices")
	}
}

func TestSocketAccessGate_blocksAccountWithoutDevice(t *testing.T) {
	mem := store.NewMemory()
	gate := &socketAccessGate{store: mem}

	account, err := mem.CreateAccount(model.User{
		Username: "no-device-user",
		Email:    "",
	}, mustHash(t, "password123"))
	if err != nil {
		t.Fatalf("create account: %v", err)
	}

	token, _, err := auth.IssueAccessToken(auth.AccessTokenInput{
		UserID:    account.ID,
		Username:  "no-device-user",
		TrustTier: model.TrustTierNew,
		SessionID: "sess-nd",
	})
	if err != nil {
		t.Fatalf("issue token: %v", err)
	}
	_ = mem.CreateSession(model.SessionRecord{
		ID:               "sess-nd",
		UserID:           account.ID,
		RefreshTokenHash: []byte("hash"),
		ExpiresAt:        store.NowUTC().Add(auth.RefreshTokenTTL),
	})

	claims, err := auth.ParseToken(token)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if err := gate.AllowSocket(claims); err == nil {
		t.Fatal("expected active account without device to be blocked from websocket")
	}
}

func mustHash(t *testing.T, password string) string {
	t.Helper()
	hash, err := auth.HashPassword(password)
	if err != nil {
		t.Fatalf("hash: %v", err)
	}
	return hash
}
