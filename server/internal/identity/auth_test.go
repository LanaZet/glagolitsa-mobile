// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"

	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func TestMain(m *testing.M) {
	_ = os.Setenv("REGISTRATION_POW_REQUIRED", "false")
	os.Exit(m.Run())
}

func newTestHandler() *Handler {
	mem := store.NewMemory()
	return NewHandler(mem, mem)
}

func newTestStoreAndHandler() (*store.MemoryStore, *Handler) {
	mem := store.NewMemory()
	return mem, NewHandler(mem, mem)
}

func postRegister(t *testing.T, h *Handler, body any) *httptest.ResponseRecorder {
	t.Helper()
	payload, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	req := httptest.NewRequest(http.MethodPost, "/api/auth/register", bytes.NewReader(payload))
	rec := httptest.NewRecorder()
	h.Register(rec, req)
	return rec
}

func postLogin(t *testing.T, h *Handler, body any) *httptest.ResponseRecorder {
	t.Helper()
	payload, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	req := httptest.NewRequest(http.MethodPost, "/api/auth/login", bytes.NewReader(payload))
	rec := httptest.NewRecorder()
	h.Login(rec, req)
	return rec
}

func postRefresh(t *testing.T, h *Handler, body any) *httptest.ResponseRecorder {
	t.Helper()
	payload, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	req := httptest.NewRequest(http.MethodPost, "/api/auth/refresh", bytes.NewReader(payload))
	rec := httptest.NewRecorder()
	h.Refresh(rec, req)
	return rec
}

func decodeAuthResponse(t *testing.T, rec *httptest.ResponseRecorder) model.AuthResponse {
	t.Helper()
	var resp model.AuthResponse
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatalf("decode auth response: %v; body=%s", err, rec.Body.String())
	}
	return resp
}

func TestRegister_success(t *testing.T) {
	h := newTestHandler()
	rec := postRegister(t, h, model.RegisterRequest{
		Username: "spinpunch",
		Password: "password123",
		Email:    "alice@example.com",
	})
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}

	var resp model.AuthResponse
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if resp.Token == "" || resp.User.Username != "spinpunch" {
		t.Fatalf("unexpected response: %+v", resp)
	}
	if resp.TokenID == "" || resp.SessionID == "" || resp.TokenID != resp.SessionID {
		t.Fatalf("unexpected token ids: token_id=%q session_id=%q", resp.TokenID, resp.SessionID)
	}
	claims, err := auth.ParseToken(resp.Token)
	if err != nil {
		t.Fatalf("parse token: %v", err)
	}
	if claims.ID == "" || claims.SessionID == "" || claims.ID != resp.SessionID || claims.SessionID != resp.SessionID {
		t.Fatalf("unexpected claims ids: jti=%q sid=%q response=%q", claims.ID, claims.SessionID, resp.SessionID)
	}
	if resp.User.Email != "alice@example.com" {
		t.Fatalf("email = %q", resp.User.Email)
	}
}

func TestRegister_activatesAccountAndMakesItSearchable(t *testing.T) {
	mem, h := newTestStoreAndHandler()
	first := postRegister(t, h, model.RegisterRequest{
		Username: "firstuser",
		Password: "password123",
	})
	if first.Code != http.StatusCreated {
		t.Fatalf("first status = %d, body = %s", first.Code, first.Body.String())
	}
	second := postRegister(t, h, model.RegisterRequest{
		Username: "seconduser",
		Password: "password123",
	})
	if second.Code != http.StatusCreated {
		t.Fatalf("second status = %d, body = %s", second.Code, second.Body.String())
	}

	account, secondUser, err := mem.GetAccountByUsername("seconduser")
	if err != nil {
		t.Fatalf("second account: %v", err)
	}
	if account.AccountStatus != model.AccountStatusActive {
		t.Fatalf("account status = %q, want %q", account.AccountStatus, model.AccountStatusActive)
	}

	hits, err := mem.SearchProfiles("first", secondUser.ID, 10)
	if err != nil {
		t.Fatalf("search profiles: %v", err)
	}
	if len(hits) != 1 || hits[0].Username != "firstuser" {
		t.Fatalf("search hits = %+v", hits)
	}
}

func TestRegister_invalidUsername(t *testing.T) {
	h := newTestHandler()
	rec := postRegister(t, h, model.RegisterRequest{
		Username: "spin punch",
		Password: "password123",
	})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d", rec.Code)
	}
}

func TestRegister_passwordTooShort(t *testing.T) {
	h := newTestHandler()
	rec := postRegister(t, h, model.RegisterRequest{
		Username: "alice",
		Password: "short",
	})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d", rec.Code)
	}
}

func TestRegister_invalidEmail(t *testing.T) {
	h := newTestHandler()
	rec := postRegister(t, h, model.RegisterRequest{
		Username: "alice",
		Password: "password123",
		Email:    "not-an-email",
	})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d", rec.Code)
	}
}

func TestRegister_rejectsDevReservedUsername(t *testing.T) {
	h := newTestHandler()
	rec := postRegister(t, h, model.RegisterRequest{
		Username: "Polo",
		Password: "password123",
	})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestRegister_duplicateUsernameAntiEnumeration(t *testing.T) {
	h := newTestHandler()
	first := postRegister(t, h, model.RegisterRequest{
		Username: "dave",
		Password: "password123",
	})
	if first.Code != http.StatusCreated {
		t.Fatalf("first status = %d", first.Code)
	}

	second := postRegister(t, h, model.RegisterRequest{
		Username: "dave",
		Password: "password124",
	})
	if second.Code != http.StatusAccepted {
		t.Fatalf("duplicate status = %d, want 202", second.Code)
	}
}

func TestRegister_duplicateEmailAntiEnumeration(t *testing.T) {
	h := newTestHandler()
	first := postRegister(t, h, model.RegisterRequest{
		Username: "carol",
		Password: "password123",
		Email:    "carol@example.com",
	})
	if first.Code != http.StatusCreated {
		t.Fatalf("first status = %d", first.Code)
	}

	second := postRegister(t, h, model.RegisterRequest{
		Username: "caroltwo",
		Password: "password123",
		Email:    "carol@example.com",
	})
	if second.Code != http.StatusAccepted {
		t.Fatalf("duplicate email status = %d, want 202", second.Code)
	}
}

func TestRegister_normalizesUsernameAndEmail(t *testing.T) {
	h := newTestHandler()
	rec := postRegister(t, h, model.RegisterRequest{
		Username: "  Alice ",
		Password: "password123",
		Email:    "  BOB@Example.COM ",
	})
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	var resp model.AuthResponse
	_ = json.NewDecoder(rec.Body).Decode(&resp)
	if resp.User.Username != "alice" || resp.User.Email != "bob@example.com" {
		t.Fatalf("got username=%q email=%q", resp.User.Username, resp.User.Email)
	}
}

func TestRefresh_immediateAfterLoginRenewsDeviceSession(t *testing.T) {
	h := newTestHandler()
	register := postRegister(t, h, model.RegisterRequest{
		Username: "refreshuser",
		Password: "password123",
		DeviceID: "device-main",
	})
	if register.Code != http.StatusCreated {
		t.Fatalf("register status = %d, body = %s", register.Code, register.Body.String())
	}

	login := postLogin(t, h, model.LoginRequest{
		Username: "refreshuser",
		Password: "password123",
		DeviceID: "device-main",
	})
	if login.Code != http.StatusOK {
		t.Fatalf("login status = %d, body = %s", login.Code, login.Body.String())
	}
	loginResp := decodeAuthResponse(t, login)
	if loginResp.RefreshToken == "" || loginResp.SessionID == "" {
		t.Fatalf("missing login session data: %+v", loginResp)
	}

	refresh := postRefresh(t, h, model.RefreshRequest{
		RefreshToken: loginResp.RefreshToken,
		DeviceID:     "device-main",
	})
	if refresh.Code != http.StatusOK {
		t.Fatalf("refresh status = %d, body = %s", refresh.Code, refresh.Body.String())
	}
	refreshResp := decodeAuthResponse(t, refresh)
	if refreshResp.RefreshToken != loginResp.RefreshToken {
		t.Fatalf("refresh token rotated unexpectedly")
	}
	if refreshResp.SessionID != loginResp.SessionID {
		t.Fatalf("session id rotated unexpectedly: before=%q after=%q", loginResp.SessionID, refreshResp.SessionID)
	}
	if refreshResp.Token == "" {
		t.Fatalf("missing refreshed access token")
	}
	claims, err := auth.ParseToken(refreshResp.Token)
	if err != nil {
		t.Fatalf("parse refreshed token: %v", err)
	}
	if claims.DeviceID != "device-main" || claims.SessionID != refreshResp.SessionID {
		t.Fatalf("unexpected refreshed claims: %+v", claims)
	}
}

func TestRefresh_reusesDeviceBoundTokenAndRejectsMismatchedDevice(t *testing.T) {
	h := newTestHandler()
	register := postRegister(t, h, model.RegisterRequest{
		Username: "rotationuser",
		Password: "password123",
		DeviceID: "device-main",
	})
	if register.Code != http.StatusCreated {
		t.Fatalf("register status = %d, body = %s", register.Code, register.Body.String())
	}
	login := postLogin(t, h, model.LoginRequest{
		Username: "rotationuser",
		Password: "password123",
		DeviceID: "device-main",
	})
	if login.Code != http.StatusOK {
		t.Fatalf("login status = %d, body = %s", login.Code, login.Body.String())
	}
	loginResp := decodeAuthResponse(t, login)

	refresh := postRefresh(t, h, model.RefreshRequest{
		RefreshToken: loginResp.RefreshToken,
		DeviceID:     "device-main",
	})
	if refresh.Code != http.StatusOK {
		t.Fatalf("refresh status = %d, body = %s", refresh.Code, refresh.Body.String())
	}
	sameAgain := postRefresh(t, h, model.RefreshRequest{
		RefreshToken: loginResp.RefreshToken,
		DeviceID:     "device-main",
	})
	if sameAgain.Code != http.StatusOK {
		t.Fatalf("same refresh status = %d, want 200; body = %s", sameAgain.Code, sameAgain.Body.String())
	}

	wrongDevice := postRefresh(t, h, model.RefreshRequest{
		RefreshToken: loginResp.RefreshToken,
		DeviceID:     "device-other",
	})
	if wrongDevice.Code != http.StatusUnauthorized {
		t.Fatalf("wrong-device refresh status = %d, want 401; body = %s", wrongDevice.Code, wrongDevice.Body.String())
	}
}

func TestRefresh_bindsLegacySessionToProvidedDevice(t *testing.T) {
	h := newTestHandler()
	register := postRegister(t, h, model.RegisterRequest{
		Username: "legacyuser",
		Password: "password123",
	})
	if register.Code != http.StatusCreated {
		t.Fatalf("register status = %d, body = %s", register.Code, register.Body.String())
	}
	legacyResp := decodeAuthResponse(t, register)
	if legacyResp.RefreshToken == "" {
		t.Fatalf("missing legacy refresh token")
	}

	refresh := postRefresh(t, h, model.RefreshRequest{
		RefreshToken: legacyResp.RefreshToken,
		DeviceID:     "device-known",
	})
	if refresh.Code != http.StatusOK {
		t.Fatalf("legacy refresh status = %d, body = %s", refresh.Code, refresh.Body.String())
	}
	refreshResp := decodeAuthResponse(t, refresh)
	claims, err := auth.ParseToken(refreshResp.Token)
	if err != nil {
		t.Fatalf("parse refreshed token: %v", err)
	}
	if claims.DeviceID != "device-known" {
		t.Fatalf("claims device = %q, want device-known", claims.DeviceID)
	}

	next := postRefresh(t, h, model.RefreshRequest{
		RefreshToken: refreshResp.RefreshToken,
		DeviceID:     "device-known",
	})
	if next.Code != http.StatusOK {
		t.Fatalf("bound refresh status = %d, body = %s", next.Code, next.Body.String())
	}
	nextResp := decodeAuthResponse(t, next)
	if nextResp.RefreshToken != refreshResp.RefreshToken {
		t.Fatalf("legacy-bound refresh rotated unexpectedly")
	}
}
