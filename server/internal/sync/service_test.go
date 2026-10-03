// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package sync

import (
	"context"
	"testing"

	"glagolitsa/server/internal/store"
)

func TestDeltaSyncAfterEventID(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{DefaultEventLimit: 50, MaxEventLimit: 100})

	userID := "user-1"
	_, _, _ = mem.CreateGroup(store.CreateGroupInput{GroupID: "chat-1", Title: "Test", CreatorID: userID})

	_, _ = mem.AppendSyncEvent(store.AppendSyncEventInput{
		ChatID: "chat-1", MessageID: "m1", Operation: "message.created", Version: 1, ActorID: userID,
	})
	e2, _ := mem.AppendSyncEvent(store.AppendSyncEventInput{
		ChatID: "chat-1", MessageID: "m2", Operation: "message.created", Version: 1, ActorID: userID,
	})

	first, err := svc.ListEvents(userID, 0, 10)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(first.Events) < 2 {
		t.Fatalf("expected at least 2 events, got %d", len(first.Events))
	}

	second, err := svc.ListEvents(userID, first.Events[0].EventID, 10)
	if err != nil {
		t.Fatalf("delta: %v", err)
	}
	if len(second.Events) == 0 || second.Events[0].EventID <= first.Events[0].EventID {
		t.Fatal("expected events after cursor")
	}
	if second.Events[len(second.Events)-1].EventID != e2.EventID {
		t.Fatalf("expected last event %d", e2.EventID)
	}
}

func TestConflictResolverRejectsStaleVersion(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{})
	userID := "user-1"
	_, _, _ = mem.CreateGroup(store.CreateGroupInput{GroupID: "chat-1", Title: "G", CreatorID: userID})
	_, _ = mem.RegisterSyncDevice(userID, store.RegisterSyncDeviceInput{DeviceID: "d1"})

	_, _ = mem.AppendSyncEvent(store.AppendSyncEventInput{
		ChatID: "chat-1", MessageID: "m1", Operation: "message.edited", Version: 2, ActorID: userID,
	})

	resp, err := svc.PushEvent(userID, PushEventRequest{
		DeviceID: "d1", ClientEventID: "c1", Operation: "message.edited",
		ChatID: "chat-1", MessageID: "m1", Version: 1,
	})
	if err != nil {
		t.Fatalf("push: %v", err)
	}
	if !resp.Rejected {
		t.Fatal("expected stale version rejection")
	}
}

func TestOfflineQueueRetryAppliesEvent(t *testing.T) {
	mem := store.NewMemory()
	svc := NewService(mem, Config{})
	userID := "user-1"
	_, _, _ = mem.CreateGroup(store.CreateGroupInput{GroupID: "chat-1", Title: "G", CreatorID: userID})

	queued, _ := mem.EnqueueSyncOffline(userID, store.EnqueueSyncEventInput{
		DeviceID: "d1", ClientEventID: "offline-1", Operation: "message.read",
		ChatID: "chat-1", MessageID: "m1", Version: 1,
	})
	if _, err := svc.ApplyOfflineRecord(context.Background(), queued); err != nil {
		t.Fatalf("apply offline: %v", err)
	}
	events, _ := mem.ListSyncEvents(userID, 0, 10)
	found := false
	for _, event := range events {
		if event.Operation == "message.read" {
			found = true
		}
	}
	if !found {
		t.Fatal("offline event was not applied to event log")
	}
}