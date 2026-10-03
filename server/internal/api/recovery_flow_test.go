// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"net/http"
	"testing"

	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/model"
)

func TestRecovery_keyResetsPasswordWithoutAutoLogin(t *testing.T) {
	server, mem := newFlowTestServer(t)
	username := uniqueName("rec-key")
	token, userID := registerUser(t, server.URL, username)
	registerDevice(t, server.URL, token, "dev-"+userID[:8])

	recoveryKey := "abcd-ef01-2345-6789-abcd-ef01-2345-6789"
	setup := postJSON(t, server.URL+"/api/recovery/setup", model.RecoverySetupRequest{
		RecoveryKey: recoveryKey,
	}, token)
	if setup.StatusCode != http.StatusOK {
		t.Fatalf("setup status = %d", setup.StatusCode)
	}
	setup.Body.Close()

	wrongUser := postJSON(t, server.URL+"/api/recovery/verify", model.RecoveryVerifyRequest{
		Username:    "nobody-" + username,
		RecoveryKey: recoveryKey,
	}, "")
	if wrongUser.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unknown user status = %d, want 401", wrongUser.StatusCode)
	}
	wrongUser.Body.Close()

	wrongKey := postJSON(t, server.URL+"/api/recovery/verify", model.RecoveryVerifyRequest{
		Username:    username,
		RecoveryKey: "ffff-ffff-ffff-ffff-ffff-ffff-ffff-ffff",
	}, "")
	if wrongKey.StatusCode != http.StatusUnauthorized {
		t.Fatalf("wrong key status = %d, want 401", wrongKey.StatusCode)
	}
	wrongKey.Body.Close()

	verify := postJSON(t, server.URL+"/api/recovery/verify", model.RecoveryVerifyRequest{
		Username:    username,
		RecoveryKey: "ABCDEF0123456789ABCDEF0123456789",
	}, "")
	if verify.StatusCode != http.StatusOK {
		t.Fatalf("verify status = %d", verify.StatusCode)
	}
	var ticket model.RecoveryTicketResponse
	decodeJSON(t, verify, &ticket)
	if ticket.RecoveryToken == "" || ticket.Username != username {
		t.Fatalf("unexpected ticket: %+v", ticket)
	}

	newPassword := "new-password-42"
	complete := postJSON(t, server.URL+"/api/recovery/complete", model.RecoveryCompleteRequest{
		RecoveryToken: ticket.RecoveryToken,
		NewPassword:   newPassword,
	}, "")
	if complete.StatusCode != http.StatusOK {
		t.Fatalf("complete status = %d", complete.StatusCode)
	}
	complete.Body.Close()

	reuse := postJSON(t, server.URL+"/api/recovery/complete", model.RecoveryCompleteRequest{
		RecoveryToken: ticket.RecoveryToken,
		NewPassword:   "another-password",
	}, "")
	if reuse.StatusCode != http.StatusUnauthorized {
		t.Fatalf("reused ticket status = %d, want 401", reuse.StatusCode)
	}
	reuse.Body.Close()

	oldLogin := postJSON(t, server.URL+"/api/auth/login", model.LoginRequest{
		Username: username,
		Password: "password123",
	}, "")
	if oldLogin.StatusCode != http.StatusUnauthorized {
		t.Fatalf("old password status = %d, want 401", oldLogin.StatusCode)
	}
	oldLogin.Body.Close()

	newLogin := postJSON(t, server.URL+"/api/auth/login", model.LoginRequest{
		Username: username,
		Password: newPassword,
	}, "")
	if newLogin.StatusCode != http.StatusOK {
		t.Fatalf("new password login status = %d", newLogin.StatusCode)
	}
	newLogin.Body.Close()

	session, err := mem.FindSessionByID(mustParseSession(t, token))
	if err == nil {
		t.Fatalf("old session still valid: %+v", session)
	}

	replay := postJSON(t, server.URL+"/api/recovery/verify", model.RecoveryVerifyRequest{
		Username:    username,
		RecoveryKey: recoveryKey,
	}, "")
	if replay.StatusCode != http.StatusUnauthorized {
		t.Fatalf("used recovery key still works: %d", replay.StatusCode)
	}
	replay.Body.Close()
}

func TestRecovery_trustedDeviceIssuesTicket(t *testing.T) {
	server, _ := newFlowTestServer(t)
	username := uniqueName("rec-trust")
	token, userID := registerUser(t, server.URL, username)
	deviceID := "dev-" + userID[:8]
	registerDevice(t, server.URL, token, deviceID)

	start := postJSON(t, server.URL+"/api/recovery/trusted/start", model.TrustedRecoveryStartRequest{
		Username: username,
	}, "")
	if start.StatusCode != http.StatusOK {
		t.Fatalf("start status = %d", start.StatusCode)
	}
	var started model.TrustedRecoveryStartResponse
	decodeJSON(t, start, &started)
	if started.ChallengeID == "" {
		t.Fatal("missing challenge id")
	}

	unknownStart := postJSON(t, server.URL+"/api/recovery/trusted/start", model.TrustedRecoveryStartRequest{
		Username: "nobody-" + username,
	}, "")
	if unknownStart.StatusCode != http.StatusOK {
		t.Fatalf("unknown start status = %d, want 200", unknownStart.StatusCode)
	}
	unknownStart.Body.Close()

	pending := getAuth(t, server.URL+"/api/recovery/trusted/pending", token)
	if pending.StatusCode != http.StatusOK {
		t.Fatalf("pending status = %d", pending.StatusCode)
	}
	var pendingResp model.TrustedRecoveryPendingResponse
	decodeJSON(t, pending, &pendingResp)
	if len(pendingResp.Challenges) != 1 || pendingResp.Challenges[0].ChallengeID != started.ChallengeID {
		t.Fatalf("pending = %+v", pendingResp)
	}

	approveWithoutDevice := postJSON(t, server.URL+"/api/recovery/trusted/approve", model.TrustedRecoveryApproveRequest{
		ChallengeID: started.ChallengeID,
	}, token)
	if approveWithoutDevice.StatusCode != http.StatusForbidden {
		t.Fatalf("approve without bound device status = %d, want 403", approveWithoutDevice.StatusCode)
	}
	approveWithoutDevice.Body.Close()

	trustedLogin := postJSON(t, server.URL+"/api/auth/login", model.LoginRequest{
		Username: username,
		Password: "password123",
		DeviceID: deviceID,
	}, "")
	if trustedLogin.StatusCode != http.StatusOK {
		t.Fatalf("trusted login status = %d", trustedLogin.StatusCode)
	}
	var trustedAuth model.AuthResponse
	decodeJSON(t, trustedLogin, &trustedAuth)

	approve := postJSON(t, server.URL+"/api/recovery/trusted/approve", model.TrustedRecoveryApproveRequest{
		ChallengeID: started.ChallengeID,
	}, trustedAuth.Token)
	if approve.StatusCode != http.StatusOK {
		t.Fatalf("approve status = %d", approve.StatusCode)
	}
	approve.Body.Close()

	poll := postJSON(t, server.URL+"/api/recovery/trusted/poll", model.TrustedRecoveryPollRequest{
		ChallengeID: started.ChallengeID,
	}, "")
	if poll.StatusCode != http.StatusOK {
		t.Fatalf("poll status = %d", poll.StatusCode)
	}
	var ticket model.RecoveryTicketResponse
	decodeJSON(t, poll, &ticket)
	if ticket.Status != "approved" || ticket.RecoveryToken == "" {
		t.Fatalf("poll ticket = %+v", ticket)
	}

	repeatedPoll := postJSON(t, server.URL+"/api/recovery/trusted/poll", model.TrustedRecoveryPollRequest{
		ChallengeID: started.ChallengeID,
	}, "")
	if repeatedPoll.StatusCode != http.StatusOK {
		t.Fatalf("repeated poll status = %d", repeatedPoll.StatusCode)
	}
	var repeated model.RecoveryTicketResponse
	decodeJSON(t, repeatedPoll, &repeated)
	if repeated.Status != "used" || repeated.RecoveryToken != "" {
		t.Fatalf("repeated poll issued another ticket: %+v", repeated)
	}

	complete := postJSON(t, server.URL+"/api/recovery/complete", model.RecoveryCompleteRequest{
		RecoveryToken: ticket.RecoveryToken,
		NewPassword:   "trusted-pass-99",
	}, "")
	if complete.StatusCode != http.StatusOK {
		t.Fatalf("complete status = %d", complete.StatusCode)
	}
	complete.Body.Close()

	login := postJSON(t, server.URL+"/api/auth/login", model.LoginRequest{
		Username: username,
		Password: "trusted-pass-99",
	}, "")
	if login.StatusCode != http.StatusOK {
		t.Fatalf("login after trusted recovery status = %d", login.StatusCode)
	}
	login.Body.Close()
}

func TestRecovery_unknownUserStartDoesNotEnumerate(t *testing.T) {
	server, _ := newFlowTestServer(t)
	resp := postJSON(t, server.URL+"/api/recovery/trusted/start", model.TrustedRecoveryStartRequest{
		Username: "missing-user",
	}, "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	var started model.TrustedRecoveryStartResponse
	decodeJSON(t, resp, &started)
	if started.ChallengeID == "" {
		t.Fatal("dummy challenge missing")
	}

	poll := postJSON(t, server.URL+"/api/recovery/trusted/poll", model.TrustedRecoveryPollRequest{
		ChallengeID: started.ChallengeID,
	}, "")
	if poll.StatusCode != http.StatusOK {
		t.Fatalf("poll status = %d", poll.StatusCode)
	}
	var ticket model.RecoveryTicketResponse
	decodeJSON(t, poll, &ticket)
	if ticket.Status != "pending" || ticket.RecoveryToken != "" {
		t.Fatalf("dummy poll leaked: %+v", ticket)
	}
}

func uniqueName(prefix string) string {
	return alphaTestUsername(prefix)
}

func mustParseSession(t *testing.T, token string) string {
	t.Helper()
	claims, err := auth.ParseToken(token)
	if err != nil {
		t.Fatalf("parse token: %v", err)
	}
	return claims.SessionID
}
