// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"bytes"
	"io"
	"net/http"
	"strings"
	"testing"

	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/model"
)

// Login must not leak whether username exists (same status for wrong user / wrong password).
func TestLoginSecurity_unknownUserAndWrongPasswordSameStatus(t *testing.T) {
	server, _ := newFlowTestServer(t)
	username := alphaTestUsername("known")
	registerUser(t, server.URL, username)

	unknown := postJSON(t, server.URL+"/api/auth/login", model.LoginRequest{
		Username: "nobody-" + username,
		Password: "password123",
	}, "")
	defer unknown.Body.Close()
	if unknown.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unknown user status = %d, want 401", unknown.StatusCode)
	}

	wrongPass := postJSON(t, server.URL+"/api/auth/login", model.LoginRequest{
		Username: username,
		Password: "not-the-password",
	}, "")
	defer wrongPass.Body.Close()
	if wrongPass.StatusCode != http.StatusUnauthorized {
		t.Fatalf("wrong password status = %d, want 401", wrongPass.StatusCode)
	}
}

func TestLoginSecurity_rejectsBlankCredentials(t *testing.T) {
	server, _ := newFlowTestServer(t)

	resp := postJSON(t, server.URL+"/api/auth/login", model.LoginRequest{
		Username: "",
		Password: "password123",
	}, "")
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("blank username status = %d, want 400", resp.StatusCode)
	}
}

func TestLoginSecurity_validLoginDoesNotGrantChatsWithoutDevice(t *testing.T) {
	server, mem := newFlowTestServer(t)
	username := alphaTestUsername("logingate")
	password := "password123"
	_, userID := registerUser(t, server.URL, username)

	loginResp := postJSON(t, server.URL+"/api/auth/login", model.LoginRequest{
		Username: username,
		Password: password,
	}, "")
	if loginResp.StatusCode != http.StatusOK {
		t.Fatalf("login status = %d, want 200", loginResp.StatusCode)
	}
	var authResp model.AuthResponse
	decodeJSON(t, loginResp, &authResp)

	count, err := mem.CountActiveDevices(userID)
	if err != nil {
		t.Fatalf("count devices: %v", err)
	}
	if count != 0 {
		t.Fatalf("expected no devices before registration, got %d", count)
	}

	chats := getAuth(t, server.URL+"/api/chats", authResp.Token)
	defer chats.Body.Close()
	if chats.StatusCode != http.StatusForbidden {
		t.Fatalf("chats without device status = %d, want 403", chats.StatusCode)
	}
	body, _ := io.ReadAll(chats.Body)
	if !strings.Contains(string(body), pendingDeviceMessage) {
		t.Fatalf("expected %q, got %q", pendingDeviceMessage, string(body))
	}
}

func TestLoginSecurity_revokedSessionCannotAccessProtectedRoutes(t *testing.T) {
	server, mem := newFlowTestServer(t)
	username := alphaTestUsername("revoked")
	token, userID := registerUser(t, server.URL, username)
	registerDevice(t, server.URL, token, "dev-"+userID[:8])

	claims, err := auth.ParseToken(token)
	if err != nil {
		t.Fatalf("parse token: %v", err)
	}
	if err := mem.RevokeSession(claims.SessionID); err != nil {
		t.Fatalf("revoke session: %v", err)
	}

	resp := getAuth(t, server.URL+"/api/chats", token)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("revoked session status = %d, want 401", resp.StatusCode)
	}
}

func TestLoginSecurity_rejectsOverlongPassword(t *testing.T) {
	server, _ := newFlowTestServer(t)
	resp := postJSON(t, server.URL+"/api/auth/login", model.LoginRequest{
		Username: "alice",
		Password: string(bytes.Repeat([]byte{'x'}, auth.PasswordMaximumLength+1)),
	}, "")
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("overlong password status = %d, want 400", resp.StatusCode)
	}
}
