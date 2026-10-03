// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func TestMarkConnectedRequiresMediaPathWhenLiveKitEnabled(t *testing.T) {
	h := newMarkConnectedTestHandler(t)
	token := issueCallTestToken(t, "alice")

	rec := postMarkConnected(t, h, token, "call-1", `{}`)
	if rec.Code != http.StatusConflict {
		t.Fatalf("status without media confirmation = %d, want %d body=%s", rec.Code, http.StatusConflict, rec.Body.String())
	}

	rec = postMarkConnected(t, h, token, "call-1", `{"media_path_confirmed":true}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("status with media confirmation = %d, want %d body=%s", rec.Code, http.StatusOK, rec.Body.String())
	}
}

func TestCreateCallRejectsWhenParticipantAlreadyHasLiveCall(t *testing.T) {
	mem := store.NewMemory()
	createdAt := store.NowUTC()
	if _, err := mem.CreateCall(model.CallSession{
		ID:              "call-live",
		CallerID:        "alice",
		CalleeID:        "bob",
		StartedByUserID: "alice",
		CallType:        model.CallTypeAudio,
		Status:          model.CallStatusRinging,
		LivekitRoomID:   "call-call-live",
		CreatedAt:       createdAt,
	}); err != nil {
		t.Fatalf("seed call: %v", err)
	}
	h := NewHandler(mem, Config{
		Features: FeatureSet{
			Audio:        true,
			RTCAvailable: true,
		},
	}, nil, nil)
	token := issueCallTestToken(t, "bob")

	rec := postCreateCall(t, h, token, `{"callee_id":"alice","call_type":"audio","device_id":"bob-device"}`)
	if rec.Code != http.StatusConflict {
		t.Fatalf("status = %d, want %d body=%s", rec.Code, http.StatusConflict, rec.Body.String())
	}
}

func TestJobSweepClearsStaleRingingThenCreateSucceeds(t *testing.T) {
	// Create no longer blocks on request-path sweep (client 15s timeouts).
	// Jobs TypeCallTimeoutSweep owns ringing→missed.
	mem := store.NewMemory()
	createdAt := store.NowUTC().Add(-RingingTimeout - time.Second)
	if _, err := mem.CreateCall(model.CallSession{
		ID:              "call-stale",
		CallerID:        "alice",
		CalleeID:        "bob",
		StartedByUserID: "alice",
		CallType:        model.CallTypeAudio,
		Status:          model.CallStatusRinging,
		LivekitRoomID:   "call-call-stale",
		CreatedAt:       createdAt,
	}); err != nil {
		t.Fatalf("seed call: %v", err)
	}
	n, err := SweepStaleCalls(context.Background(), mem, nil, nil)
	if err != nil {
		t.Fatalf("sweep: %v", err)
	}
	if n != 1 {
		t.Fatalf("sweep n=%d want 1", n)
	}
	h := NewHandler(mem, Config{
		Features: FeatureSet{
			Audio:        true,
			RTCAvailable: true,
		},
	}, nil, nil)
	token := issueCallTestToken(t, "bob")

	rec := postCreateCall(t, h, token, `{"callee_id":"alice","call_type":"audio","device_id":"bob-device"}`)
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want %d body=%s", rec.Code, http.StatusCreated, rec.Body.String())
	}
	stale, err := mem.GetCall("call-stale")
	if err != nil {
		t.Fatalf("load stale call: %v", err)
	}
	if stale.Status != model.CallStatusMissed {
		t.Fatalf("stale status = %q, want %q", stale.Status, model.CallStatusMissed)
	}
}

func TestCreateCallReclaimsOwnRingingOnRetry(t *testing.T) {
	mem := store.NewMemory()
	createdAt := store.NowUTC()
	if _, err := mem.CreateCall(model.CallSession{
		ID:              "call-retry",
		CallerID:        "alice",
		CalleeID:        "bob",
		StartedByUserID: "alice",
		CallType:        model.CallTypeAudio,
		Status:          model.CallStatusRinging,
		LivekitRoomID:   "call-call-retry",
		CreatedAt:       createdAt,
	}); err != nil {
		t.Fatalf("seed call: %v", err)
	}
	h := NewHandler(mem, Config{
		Features: FeatureSet{
			Audio:        true,
			RTCAvailable: true,
		},
	}, nil, nil)
	token := issueCallTestToken(t, "alice")

	rec := postCreateCall(t, h, token, `{"callee_id":"bob","call_type":"audio","device_id":"alice-device"}`)
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want %d body=%s", rec.Code, http.StatusCreated, rec.Body.String())
	}
	var resp model.CallActionResponse
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if resp.Call.ID != "call-retry" {
		t.Fatalf("reclaimed id = %q, want call-retry", resp.Call.ID)
	}
}

func TestCreateCallSupersedesOwnRingingToDifferentPeer(t *testing.T) {
	mem := store.NewMemory()
	createdAt := store.NowUTC()
	if _, err := mem.CreateCall(model.CallSession{
		ID:              "call-old",
		CallerID:        "alice",
		CalleeID:        "bob",
		StartedByUserID: "alice",
		CallType:        model.CallTypeAudio,
		Status:          model.CallStatusRinging,
		LivekitRoomID:   "call-call-old",
		CreatedAt:       createdAt,
	}); err != nil {
		t.Fatalf("seed call: %v", err)
	}
	h := NewHandler(mem, Config{
		Features: FeatureSet{
			Audio:        true,
			RTCAvailable: true,
		},
	}, nil, nil)
	token := issueCallTestToken(t, "alice")

	rec := postCreateCall(t, h, token, `{"callee_id":"carol","call_type":"audio","device_id":"alice-device"}`)
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want %d body=%s", rec.Code, http.StatusCreated, rec.Body.String())
	}
	old, err := mem.GetCall("call-old")
	if err != nil {
		t.Fatalf("load old call: %v", err)
	}
	if old.Status != model.CallStatusMissed {
		t.Fatalf("old status = %q, want %q", old.Status, model.CallStatusMissed)
	}
}

func TestCreateAndJoinGroupChatCallByMember(t *testing.T) {
	mem := store.NewMemory()
	if _, err := mem.CreateChat(model.Chat{
		ID:        "group-1",
		Title:     "Team",
		Type:      model.ChatTypeGroup,
		MemberIDs: []string{"alice", "bob", "carol"},
	}); err != nil {
		t.Fatalf("seed chat: %v", err)
	}
	_ = mem.EnsureManagedGroup("group-1", "alice")
	h := NewHandler(mem, Config{
		Features: FeatureSet{Audio: true, GroupReadyAPI: true, RTCAvailable: true},
	}, nil, nil)

	rec := postCreateCall(t, h, issueCallTestToken(t, "alice"),
		`{"chat_id":"group-1","call_type":"audio","device_id":"alice-device","call_scope":"group"}`)
	if rec.Code != http.StatusCreated {
		t.Fatalf("create status=%d body=%s", rec.Code, rec.Body.String())
	}
	var created model.CallActionResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &created); err != nil {
		t.Fatalf("decode create: %v", err)
	}
	if created.Call.ChatID != "group-1" || created.Call.CallScope != model.CallScopeGroup {
		t.Fatalf("created=%+v", created.Call)
	}

	req := httptest.NewRequest(http.MethodPost, "/api/calls/"+created.Call.ID+"/join", nil)
	req.Header.Set("Authorization", "Bearer "+issueCallTestToken(t, "carol"))
	req.Header.Set("X-Device-Id", "carol-device")
	req.SetPathValue("id", created.Call.ID)
	joinRec := httptest.NewRecorder()
	httpx.WithAuth(h.JoinCall)(joinRec, req)
	if joinRec.Code != http.StatusOK {
		t.Fatalf("join status=%d body=%s", joinRec.Code, joinRec.Body.String())
	}
	var joined model.CallActionResponse
	if err := json.Unmarshal(joinRec.Body.Bytes(), &joined); err != nil {
		t.Fatalf("decode join: %v", err)
	}
	found := false
	for _, p := range joined.Call.Participants {
		if p.UserID == "carol" {
			found = true
		}
	}
	if !found {
		t.Fatalf("carol not in participants: %+v", joined.Call.Participants)
	}

	stranger := httptest.NewRequest(http.MethodPost, "/api/calls/"+created.Call.ID+"/join", nil)
	stranger.Header.Set("Authorization", "Bearer "+issueCallTestToken(t, "dave"))
	stranger.SetPathValue("id", created.Call.ID)
	strangerRec := httptest.NewRecorder()
	httpx.WithAuth(h.JoinCall)(strangerRec, stranger)
	if strangerRec.Code != http.StatusForbidden {
		t.Fatalf("stranger join status=%d want 403 body=%s", strangerRec.Code, strangerRec.Body.String())
	}
}

func TestCreateGroupChatCallRequiresGroupReadyFeature(t *testing.T) {
	mem := store.NewMemory()
	if _, err := mem.CreateChat(model.Chat{
		ID:        "group-disabled",
		Title:     "Team",
		Type:      model.ChatTypeGroup,
		MemberIDs: []string{"alice", "bob"},
	}); err != nil {
		t.Fatalf("seed chat: %v", err)
	}
	_ = mem.EnsureManagedGroup("group-disabled", "alice")
	h := NewHandler(mem, Config{
		Features: FeatureSet{Audio: true, RTCAvailable: true},
	}, nil, nil)

	rec := postCreateCall(t, h, issueCallTestToken(t, "alice"),
		`{"chat_id":"group-disabled","call_type":"audio","device_id":"alice-device","call_scope":"group"}`)
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("create status=%d want 503 body=%s", rec.Code, rec.Body.String())
	}
}

func newMarkConnectedTestHandler(t *testing.T) *Handler {
	t.Helper()
	mem := store.NewMemory()
	_, err := mem.CreateCall(model.CallSession{
		ID:              "call-1",
		CallerID:        "alice",
		CalleeID:        "bob",
		StartedByUserID: "alice",
		CallType:        model.CallTypeAudio,
		Status:          model.CallStatusConnecting,
		LivekitRoomID:   "call-call-1",
		CreatedAt:       time.Date(2026, 8, 8, 12, 0, 0, 0, time.UTC),
	})
	if err != nil {
		t.Fatalf("create call: %v", err)
	}
	h := NewHandler(mem, Config{
		Features: FeatureSet{
			RTCLiveKit: true,
		},
	}, nil, nil)
	return h
}

func postCreateCall(t *testing.T, h *Handler, token, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/calls", bytes.NewReader([]byte(body)))
	req.Header.Set("Authorization", "Bearer "+token)
	rec := httptest.NewRecorder()
	httpx.WithAuth(h.CreateCall)(rec, req)
	return rec
}

func issueCallTestToken(t *testing.T, userID string) string {
	t.Helper()
	token, _, err := auth.IssueAccessToken(auth.AccessTokenInput{
		UserID:    userID,
		Username:  userID,
		TrustTier: "trusted",
	})
	if err != nil {
		t.Fatalf("issue token: %v", err)
	}
	return token
}

func postMarkConnected(t *testing.T, h *Handler, token, callID, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/calls/"+callID+"/connected", bytes.NewReader([]byte(body)))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("X-Device-Id", "alice-device")
	req.SetPathValue("id", callID)
	rec := httptest.NewRecorder()
	httpx.WithAuth(h.MarkConnected)(rec, req)
	return rec
}
