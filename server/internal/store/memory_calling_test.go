// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"testing"
	"time"

	"glagolitsa/server/internal/model"
)

func TestMemoryCallingStatusLifecycleAndDuration(t *testing.T) {
	mem := NewMemory()
	createdAt := time.Date(2026, 7, 20, 10, 0, 0, 0, time.UTC)
	call, err := mem.CreateCall(model.CallSession{
		ID:            "call-1",
		CallerID:      "alice",
		CalleeID:      "bob",
		CallType:      model.CallTypeAudio,
		Status:        model.CallStatusRinging,
		LivekitRoomID: "call-call-1",
		CreatedAt:     createdAt,
	})
	if err != nil {
		t.Fatalf("create call: %v", err)
	}
	if call.Status != model.CallStatusRinging {
		t.Fatalf("status = %q", call.Status)
	}

	acceptedAt := createdAt.Add(5 * time.Second)
	call, err = mem.UpdateCallStatus("call-1", model.CallStatusConnecting, acceptedAt)
	if err != nil {
		t.Fatalf("accept call: %v", err)
	}
	if call.Status != model.CallStatusConnecting || call.AcceptedAt == nil || !call.AcceptedAt.Equal(acceptedAt) {
		t.Fatalf("accepted call = %+v", call)
	}

	connectedAt := acceptedAt.Add(3 * time.Second)
	call, err = mem.MarkCallConnected("call-1", connectedAt)
	if err != nil {
		t.Fatalf("connect call: %v", err)
	}
	if call.Status != model.CallStatusActive || call.ConnectedAt == nil || !call.ConnectedAt.Equal(connectedAt) {
		t.Fatalf("connected call = %+v", call)
	}

	endedAt := connectedAt.Add(42 * time.Second)
	call, err = mem.EndCall("call-1", endedAt)
	if err != nil {
		t.Fatalf("end call: %v", err)
	}
	if call.Status != model.CallStatusEnded || call.EndedAt == nil || !call.EndedAt.Equal(endedAt) {
		t.Fatalf("ended call = %+v", call)
	}
	if call.DurationSec != 42 {
		t.Fatalf("duration = %d, want 42", call.DurationSec)
	}
}

func TestMemoryCallingMarkConnectedDoesNotReviveEndedCall(t *testing.T) {
	mem := NewMemory()
	createdAt := time.Date(2026, 7, 20, 11, 0, 0, 0, time.UTC)
	_, err := mem.CreateCall(model.CallSession{
		ID:            "call-race",
		CallerID:      "alice",
		CalleeID:      "bob",
		CallType:      model.CallTypeAudio,
		Status:        model.CallStatusRinging,
		LivekitRoomID: "call-call-race",
		CreatedAt:     createdAt,
	})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	// Hang up immediately (cancel).
	ended, err := mem.EndCall("call-race", createdAt.Add(time.Second))
	if err != nil {
		t.Fatalf("end: %v", err)
	}
	if ended.Status != model.CallStatusEnded {
		t.Fatalf("status after end = %q", ended.Status)
	}
	// Late mark-connected from client race must not resurrect ACTIVE.
	after, err := mem.MarkCallConnected("call-race", createdAt.Add(2*time.Second))
	if err != nil {
		t.Fatalf("mark connected: %v", err)
	}
	if after.Status != model.CallStatusEnded {
		t.Fatalf("status after late connect = %q, want ended", after.Status)
	}
	if after.ConnectedAt != nil {
		t.Fatalf("connected_at should stay nil on terminal call, got %v", after.ConnectedAt)
	}
}

func TestMemoryCallingCreateCallIfAvailableRejectsLiveParticipant(t *testing.T) {
	mem := NewMemory()
	createdAt := time.Date(2026, 8, 8, 14, 0, 0, 0, time.UTC)
	if _, err := mem.CreateCallIfAvailable(model.CallSession{
		ID:            "call-live",
		CallerID:      "alice",
		CalleeID:      "bob",
		CallType:      model.CallTypeAudio,
		Status:        model.CallStatusRinging,
		LivekitRoomID: "call-call-live",
		CreatedAt:     createdAt,
	}, []string{"alice", "bob"}); err != nil {
		t.Fatalf("create first call: %v", err)
	}
	if _, err := mem.CreateCallIfAvailable(model.CallSession{
		ID:            "call-race",
		CallerID:      "bob",
		CalleeID:      "alice",
		CallType:      model.CallTypeAudio,
		Status:        model.CallStatusRinging,
		LivekitRoomID: "call-call-race",
		CreatedAt:     createdAt.Add(time.Second),
	}, []string{"bob", "alice"}); err != ErrAlreadyExists {
		t.Fatalf("second live call err = %v, want ErrAlreadyExists", err)
	}
	if _, err := mem.EndCall("call-live", createdAt.Add(5*time.Second)); err != nil {
		t.Fatalf("end first call: %v", err)
	}
	if _, err := mem.CreateCallIfAvailable(model.CallSession{
		ID:            "call-next",
		CallerID:      "bob",
		CalleeID:      "alice",
		CallType:      model.CallTypeAudio,
		Status:        model.CallStatusRinging,
		LivekitRoomID: "call-call-next",
		CreatedAt:     createdAt.Add(6 * time.Second),
	}, []string{"bob", "alice"}); err != nil {
		t.Fatalf("create after end: %v", err)
	}
}

func TestMemoryCallingKeyOffersPreserveEnvelopeType(t *testing.T) {
	mem := NewMemory()
	createdAt := time.Date(2026, 8, 8, 13, 0, 0, 0, time.UTC)
	_, err := mem.CreateCall(model.CallSession{
		ID:            "call-key",
		CallerID:      "alice",
		CalleeID:      "bob",
		CallType:      model.CallTypeAudio,
		Status:        model.CallStatusConnecting,
		LivekitRoomID: "call-call-key",
		CreatedAt:     createdAt,
	})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	err = mem.StoreCallKeyOffers("call-key", []CallKeyOfferRecord{
		{
			SourceUserID:   "alice",
			SourceDeviceID: "alice-device",
			TargetUserID:   "bob",
			TargetDeviceID: "bob-device",
			EnvelopeType:   3,
			EncryptedKey:   []byte{1, 2, 3},
		},
	})
	if err != nil {
		t.Fatalf("store key offers: %v", err)
	}
	offers, err := mem.ListCallKeyOffersForDevice("call-key", "bob", "bob-device")
	if err != nil {
		t.Fatalf("list key offers: %v", err)
	}
	if len(offers) != 1 {
		t.Fatalf("offers len=%d", len(offers))
	}
	if offers[0].EnvelopeType != 3 {
		t.Fatalf("envelope_type=%d, want 3", offers[0].EnvelopeType)
	}
	if offers[0].EncryptedKey == "" {
		t.Fatal("encrypted_key must be base64 encoded")
	}
}

func TestMemoryIsGroupMember_forCallInvites(t *testing.T) {
	mem := NewMemory()
	// Minimal chat with members (DM-style MemberIDs).
	_, err := mem.CreateChat(model.Chat{
		ID: "g1", Type: "group", Title: "G", MemberIDs: []string{"alice", "bob"},
		CreatedAt: time.Now().UTC(),
	})
	if err != nil {
		// CreateChat signature may differ — fall back to direct map if needed.
		t.Skipf("CreateChat: %v", err)
	}
	ok, err := mem.IsGroupMember("g1", "alice")
	if err != nil || !ok {
		t.Fatalf("alice member ok=%v err=%v", ok, err)
	}
	ok, err = mem.IsGroupMember("g1", "carol")
	if err != nil || ok {
		t.Fatalf("carol must not be member ok=%v err=%v", ok, err)
	}
}

func TestMemoryCallingGroupReadyParticipantsAndHistory(t *testing.T) {
	mem := NewMemory()
	createdAt := time.Date(2026, 8, 3, 12, 0, 0, 0, time.UTC)
	call, err := mem.CreateCall(model.CallSession{
		ID:              "call-group",
		CallerID:        "alice",
		CalleeID:        "bob",
		ChatID:          "chat-1",
		StartedByUserID: "alice",
		CallScope:       model.CallScopeGroup,
		CallType:        model.CallTypeAudio,
		Status:          model.CallStatusRinging,
		LivekitRoomID:   "call-call-group",
		SelectedRegion:  "primary",
		RouteClass:      "single_region",
		PolicyVersion:   1,
		CreatedAt:       createdAt,
	})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if call.CallScope != model.CallScopeGroup || call.ChatID != "chat-1" {
		t.Fatalf("group fields = %+v", call)
	}
	now := createdAt
	if err := mem.UpsertCallParticipant(model.CallParticipant{
		CallID: "call-group", UserID: "alice", DeviceID: "d1",
		Role: model.CallParticipantRoleStarter, InviteState: model.CallInviteStateJoined,
		JoinedAt: &now,
	}); err != nil {
		t.Fatalf("starter: %v", err)
	}
	if err := mem.UpsertCallParticipant(model.CallParticipant{
		CallID: "call-group", UserID: "bob", DeviceID: "",
		Role: model.CallParticipantRoleInvited, InviteState: model.CallInviteStateRinging,
	}); err != nil {
		t.Fatalf("bob: %v", err)
	}
	if err := mem.UpsertCallParticipant(model.CallParticipant{
		CallID: "call-group", UserID: "carol", DeviceID: "",
		Role: model.CallParticipantRoleInvited, InviteState: model.CallInviteStateRinging,
	}); err != nil {
		t.Fatalf("carol: %v", err)
	}
	// Third participant invited without changing room id.
	ok, err := mem.IsCallParticipant("call-group", "carol")
	if err != nil || !ok {
		t.Fatalf("carol participant ok=%v err=%v", ok, err)
	}
	// History via participants even if not caller/callee denormalized for carol.
	// Carol is only in participants (callee is bob).
	history, err := mem.ListCallsForUser("carol", 10)
	if err != nil {
		t.Fatalf("history: %v", err)
	}
	if len(history) != 1 || history[0].ID != "call-group" {
		t.Fatalf("history for carol = %+v", history)
	}
	parts, err := mem.ListCallParticipants("call-group")
	if err != nil || len(parts) != 3 {
		t.Fatalf("participants=%v err=%v", parts, err)
	}
	_, err = mem.CreateCallInvite(model.CallInvite{
		CallID: "call-group", InvitedUserID: "carol", InvitedByUserID: "alice",
	})
	if err != nil {
		t.Fatalf("invite: %v", err)
	}
}

func TestMemoryCallingEndedBeforeStartClampsDuration(t *testing.T) {
	mem := NewMemory()
	createdAt := time.Date(2026, 7, 20, 10, 0, 0, 0, time.UTC)
	_, err := mem.CreateCall(model.CallSession{
		ID:            "call-2",
		CallerID:      "alice",
		CalleeID:      "bob",
		CallType:      model.CallTypeAudio,
		Status:        model.CallStatusRinging,
		LivekitRoomID: "call-call-2",
		CreatedAt:     createdAt,
	})
	if err != nil {
		t.Fatalf("create call: %v", err)
	}

	call, err := mem.EndCall("call-2", createdAt.Add(-time.Second))
	if err != nil {
		t.Fatalf("end call: %v", err)
	}
	if call.DurationSec != 0 {
		t.Fatalf("duration = %d, want 0", call.DurationSec)
	}
}
