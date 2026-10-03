// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"context"
	"testing"
	"time"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func TestSweepStaleCalls_ringingBecomesMissed(t *testing.T) {
	mem := store.NewMemory()
	old := time.Now().UTC().Add(-2 * time.Minute)
	_, err := mem.CreateCall(model.CallSession{
		ID: "c1", CallerID: "a", CalleeID: "b",
		Status: model.CallStatusRinging, LivekitRoomID: "call-c1", CreatedAt: old,
	})
	if err != nil {
		t.Fatal(err)
	}
	n, err := SweepStaleCalls(context.Background(), mem, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("n=%d", n)
	}
	got, err := mem.GetCall("c1")
	if err != nil {
		t.Fatal(err)
	}
	if got.Status != model.CallStatusMissed {
		t.Fatalf("status=%q", got.Status)
	}
}

func TestSweepStaleCallsClearsPresence(t *testing.T) {
	mem := store.NewMemory()
	old := time.Now().UTC().Add(-2 * time.Minute)
	_, err := mem.CreateCall(model.CallSession{
		ID: "c-presence", CallerID: "a", CalleeID: "b",
		Status: model.CallStatusRinging, LivekitRoomID: "call-c-presence", CreatedAt: old,
	})
	if err != nil {
		t.Fatal(err)
	}
	presence := &timeoutPresenceSpy{}
	n, err := SweepStaleCalls(context.Background(), mem, nil, presence)
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("n=%d", n)
	}
	if !presence.cleared["a"] || !presence.cleared["b"] {
		t.Fatalf("cleared=%v, want caller and callee", presence.cleared)
	}
}

func TestSweepStaleCalls_activeEmptyEnds(t *testing.T) {
	mem := store.NewMemory()
	connected := time.Now().UTC().Add(-ActiveEmptyTimeout - time.Second)
	_, err := mem.CreateCall(model.CallSession{
		ID: "c-empty", CallerID: "a", CalleeID: "b",
		Status: model.CallStatusActive, LivekitRoomID: "call-c-empty",
		CreatedAt: connected.Add(-time.Minute), ConnectedAt: &connected,
	})
	if err != nil {
		t.Fatal(err)
	}
	// No joined participants → empty room after ActiveEmptyTimeout.
	n, err := SweepStaleCalls(context.Background(), mem, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("n=%d, want 1", n)
	}
	got, err := mem.GetCall("c-empty")
	if err != nil {
		t.Fatal(err)
	}
	if got.Status != model.CallStatusEnded {
		t.Fatalf("status=%q, want ended", got.Status)
	}
}

func TestSweepStaleCalls_activeWithJoinedSurvivesUntilMax(t *testing.T) {
	mem := store.NewMemory()
	connected := time.Now().UTC().Add(-30 * time.Minute)
	_, err := mem.CreateCall(model.CallSession{
		ID: "c-live", CallerID: "a", CalleeID: "b",
		Status: model.CallStatusActive, LivekitRoomID: "call-c-live",
		CreatedAt: connected.Add(-time.Minute), ConnectedAt: &connected,
	})
	if err != nil {
		t.Fatal(err)
	}
	now := store.NowUTC()
	if err := mem.UpsertCallParticipant(model.CallParticipant{
		CallID: "c-live", UserID: "a", Role: model.CallParticipantRoleStarter,
		InviteState: model.CallInviteStateJoined, MediaState: model.CallMediaAudioOnly,
		JoinedAt: &now,
	}); err != nil {
		t.Fatal(err)
	}
	n, err := SweepStaleCalls(context.Background(), mem, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatalf("n=%d, want 0 (still legitimately active)", n)
	}
}

func TestSweepStaleCalls_activeHardCapEndsEvenWithJoined(t *testing.T) {
	mem := store.NewMemory()
	connected := time.Now().UTC().Add(-ActiveMaxDuration - time.Minute)
	_, err := mem.CreateCall(model.CallSession{
		ID: "c-zombie", CallerID: "a", CalleeID: "b",
		Status: model.CallStatusActive, LivekitRoomID: "call-c-zombie",
		CreatedAt: connected.Add(-time.Minute), ConnectedAt: &connected,
	})
	if err != nil {
		t.Fatal(err)
	}
	now := store.NowUTC()
	if err := mem.UpsertCallParticipant(model.CallParticipant{
		CallID: "c-zombie", UserID: "a", Role: model.CallParticipantRoleStarter,
		InviteState: model.CallInviteStateJoined, MediaState: model.CallMediaAudioOnly,
		JoinedAt: &now,
	}); err != nil {
		t.Fatal(err)
	}
	n, err := SweepStaleCalls(context.Background(), mem, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("n=%d, want 1 (hard max)", n)
	}
	got, err := mem.GetCall("c-zombie")
	if err != nil {
		t.Fatal(err)
	}
	if got.Status != model.CallStatusEnded {
		t.Fatalf("status=%q, want ended", got.Status)
	}
}

func TestAcceptIdempotent_viaStoreStatus(t *testing.T) {
	// Smoke that connecting calls stay connecting on re-accept path in store.
	mem := store.NewMemory()
	now := time.Now().UTC()
	_, err := mem.CreateCall(model.CallSession{
		ID: "c2", CallerID: "a", CalleeID: "b",
		Status: model.CallStatusRinging, LivekitRoomID: "call-c2", CreatedAt: now,
	})
	if err != nil {
		t.Fatal(err)
	}
	u1, err := mem.UpdateCallStatus("c2", model.CallStatusConnecting, now)
	if err != nil {
		t.Fatal(err)
	}
	u2, err := mem.UpdateCallStatus("c2", model.CallStatusConnecting, now.Add(time.Second))
	if err != nil {
		t.Fatal(err)
	}
	if u1.Status != model.CallStatusConnecting || u2.Status != model.CallStatusConnecting {
		t.Fatalf("u1=%+v u2=%+v", u1, u2)
	}
}

type timeoutPresenceSpy struct {
	cleared map[string]bool
}

func (s *timeoutPresenceSpy) SetInCall(userID, callID string, participantIDs []string) error {
	return nil
}

func (s *timeoutPresenceSpy) ClearInCall(userID string, participantIDs []string) error {
	if s.cleared == nil {
		s.cleared = map[string]bool{}
	}
	s.cleared[userID] = true
	return nil
}
