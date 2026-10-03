// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func TestMain(m *testing.M) {
	_ = os.Setenv("REGISTRATION_POW_REQUIRED", "false")
	os.Exit(m.Run())
}

func newFlowTestServer(t *testing.T) (*httptest.Server, *store.MemoryStore) {
	t.Helper()
	mem := store.NewMemory()
	handler := NewHandler(Options{Store: mem, FrontendOrigin: "*"})
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	return server, mem
}

func postJSON(t *testing.T, url string, body any, token string) *http.Response {
	t.Helper()
	payload, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	return resp
}

func getAuth(t *testing.T, url, token string) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	return resp
}

func patchJSON(t *testing.T, url string, body any, token string) *http.Response {
	t.Helper()
	payload, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	req, err := http.NewRequest(http.MethodPatch, url, bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	return resp
}

func putJSON(t *testing.T, url string, body any, token string) *http.Response {
	t.Helper()
	payload, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	req, err := http.NewRequest(http.MethodPut, url, bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	return resp
}

func deleteAuth(t *testing.T, url, token string) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodDelete, url, nil)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	return resp
}

func decodeJSON[T any](t *testing.T, resp *http.Response, out *T) {
	t.Helper()
	defer resp.Body.Close()
	if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
		t.Fatalf("decode status=%d: %v", resp.StatusCode, err)
	}
}

func sampleDevicePayload(deviceID string) model.RegisterDeviceRequest {
	key := base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{7}, 32))
	return model.RegisterDeviceRequest{
		DeviceID:          deviceID,
		RegistrationID:    42,
		IdentityPublicKey: key,
		SignedPreKey: model.SignedPreKeyMaterial{
			ID: 1001, PublicKey: key, Signature: key, CreatedAt: 1_700_000_000_000,
		},
		PqPreKey: model.PqPreKeyMaterial{
			ID: 2001, PublicMaterial: key, Signature: key, CreatedAt: 1_700_000_000_000,
		},
		OneTimePrekeys: []model.OneTimePreKeyMaterial{
			{ID: 301, PublicKey: key},
		},
	}
}

func registerUser(t *testing.T, baseURL, username string) (token string, userID string) {
	t.Helper()
	resp := postJSON(t, baseURL+"/api/auth/register", model.RegisterRequest{
		Username: username,
		Password: "password123",
		Email:    username + "@example.com",
	}, "")
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("register status = %d", resp.StatusCode)
	}
	var authResp model.AuthResponse
	decodeJSON(t, resp, &authResp)
	if authResp.Token == "" || authResp.User.ID == "" {
		t.Fatalf("unexpected auth response: %+v", authResp)
	}
	return authResp.Token, authResp.User.ID
}

func alphaTestUsername(prefix string) string {
	raw := strings.ToLower(fmt.Sprintf("%s%d", prefix, store.NowUTC().UnixNano()))
	var b strings.Builder
	for _, r := range raw {
		switch {
		case r >= 'a' && r <= 'z':
			b.WriteRune(r)
		case r >= '0' && r <= '9':
			b.WriteByte(byte('a' + r - '0'))
		}
	}
	if b.Len() == 0 {
		return "testuser"
	}
	return b.String()
}

func registerDevice(t *testing.T, baseURL, token, deviceID string) {
	t.Helper()
	resp := postJSON(t, baseURL+"/api/devices", sampleDevicePayload(deviceID), token)
	if resp.StatusCode != http.StatusCreated {
		defer resp.Body.Close()
		t.Fatalf("register device status = %d", resp.StatusCode)
	}
	resp.Body.Close()
}

// T1: register → account active immediately.
func TestRegistrationFlow_registerCreatesActiveAccount(t *testing.T) {
	server, mem := newFlowTestServer(t)
	username := alphaTestUsername("flowactive")
	_, userID := registerUser(t, server.URL, username)

	account, err := mem.GetAccountByID(userID)
	if err != nil {
		t.Fatalf("get account: %v", err)
	}
	if account.AccountStatus != model.AccountStatusActive {
		t.Fatalf("account_status = %q, want active", account.AccountStatus)
	}
}

// T2/T5: active user without device_id cannot open chats.
func TestRegistrationFlow_activeWithoutDeviceBlockedFromChats(t *testing.T) {
	server, _ := newFlowTestServer(t)
	username := alphaTestUsername("flownodevice")
	token, _ := registerUser(t, server.URL, username)

	resp := getAuth(t, server.URL+"/api/chats", token)
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("GET /api/chats status = %d, want 403", resp.StatusCode)
	}
	resp.Body.Close()
}

// T3/T4: POST /api/devices allowed before device exists (ServeMux routeKey).
func TestRegistrationFlow_deviceRegistrationAllowedBeforeDeviceExists(t *testing.T) {
	server, mem := newFlowTestServer(t)
	username := alphaTestUsername("flowdevice")
	token, userID := registerUser(t, server.URL, username)
	deviceID := fmt.Sprintf("device-%s", userID[:8])

	registerDevice(t, server.URL, token, deviceID)

	count, err := mem.CountActiveDevices(userID)
	if err != nil {
		t.Fatalf("count devices: %v", err)
	}
	if count != 1 {
		t.Fatalf("device count = %d, want 1", count)
	}

	resp := getAuth(t, server.URL+"/api/chats", token)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /api/chats after device status = %d, want 200", resp.StatusCode)
	}
	resp.Body.Close()
}

// Legacy inactive accounts still register device and then access chats.
func TestRegistrationFlow_inactiveAccountCanRegisterDeviceThenAccessChats(t *testing.T) {
	server, mem := newFlowTestServer(t)
	username := alphaTestUsername("flowinactive")
	token, userID := registerUser(t, server.URL, username)
	if err := mem.SetAccountStatus(userID, model.AccountStatusInactive); err != nil {
		t.Fatalf("set inactive: %v", err)
	}

	resp := getAuth(t, server.URL+"/api/chats", token)
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("inactive chats status = %d, want 403", resp.StatusCode)
	}
	resp.Body.Close()

	registerDevice(t, server.URL, token, "legacy-device-"+userID[:8])

	account, err := mem.GetAccountByID(userID)
	if err != nil {
		t.Fatalf("get account: %v", err)
	}
	if account.AccountStatus != model.AccountStatusActive {
		t.Fatalf("first device should activate account, got %q", account.AccountStatus)
	}

	resp = getAuth(t, server.URL+"/api/chats", token)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("chats after device status = %d, want 200", resp.StatusCode)
	}
	resp.Body.Close()
}
