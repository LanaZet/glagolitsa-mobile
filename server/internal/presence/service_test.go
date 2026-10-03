// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package presence

import (
	"reflect"
	"sort"
	"testing"
	"time"

	"glagolitsa/server/internal/model"
)

func TestServiceInCallOverridesOnlineThenRestoresOnline(t *testing.T) {
	privacy := newPresencePrivacyFake()
	graph := &presenceGraphFake{
		contacts: map[string][]string{
			"alice": {"bob"},
			"bob":   {"alice"},
		},
	}
	pub := &presencePublisherFake{}
	store := NewMemoryStore(Config{OnlineTTL: time.Minute})
	svc := NewService(store, privacy, graph, pub, Config{
		OnlineTTL:        time.Minute,
		TypingTTL:        time.Second,
		RecordingTTL:     time.Second,
		CallTTL:          time.Minute,
		MaxUsersPerQuery: 50,
	})

	if err := svc.Heartbeat("alice", "phone"); err != nil {
		t.Fatalf("heartbeat: %v", err)
	}
	view, err := svc.ViewUser("bob", "alice")
	if err != nil {
		t.Fatalf("view online: %v", err)
	}
	if view.Status != model.PresenceOnline || view.InCall {
		t.Fatalf("online view = %+v", view)
	}

	if err := svc.SetInCall("alice", "call-1", []string{"alice", "bob", "bob"}); err != nil {
		t.Fatalf("set in call: %v", err)
	}
	view, err = svc.ViewUser("bob", "alice")
	if err != nil {
		t.Fatalf("view in call: %v", err)
	}
	if view.Status != model.PresenceInCall || !view.InCall || view.CallID != "call-1" {
		t.Fatalf("in-call view = %+v", view)
	}
	pub.requireEvent(t, "call.started", []string{"alice", "bob"})

	if err := svc.ClearInCall("alice", []string{"alice", "bob"}); err != nil {
		t.Fatalf("clear in call: %v", err)
	}
	view, err = svc.ViewUser("bob", "alice")
	if err != nil {
		t.Fatalf("view restored: %v", err)
	}
	if view.Status != model.PresenceOnline || view.InCall || view.CallID != "" {
		t.Fatalf("restored view = %+v", view)
	}
	pub.requireEvent(t, "call.ended", []string{"alice", "bob"})
}

func TestServiceHiddenPresenceDoesNotExposeCallState(t *testing.T) {
	privacy := newPresencePrivacyFake()
	privacy.settings["alice"] = model.PresencePrivacySettings{
		OnlineVisibility:   model.PresenceVisibilityNobody,
		LastSeenVisibility: model.PresenceVisibilityAll,
	}
	graph := &presenceGraphFake{
		contacts: map[string][]string{
			"alice": {"bob"},
			"bob":   {"alice"},
		},
	}
	svc := NewService(
		NewMemoryStore(Config{OnlineTTL: time.Minute}),
		privacy,
		graph,
		&presencePublisherFake{},
		Config{OnlineTTL: time.Minute, CallTTL: time.Minute, MaxUsersPerQuery: 50},
	)

	if err := svc.SetInCall("alice", "call-secret", []string{"alice", "bob"}); err != nil {
		t.Fatalf("set in call: %v", err)
	}
	view, err := svc.ViewUser("bob", "alice")
	if err != nil {
		t.Fatalf("view hidden: %v", err)
	}
	if view.Status != model.PresenceHidden || view.InCall || view.CallID != "" {
		t.Fatalf("hidden view leaked call state: %+v", view)
	}
}

func TestServiceOnlineViewDoesNotExposeStaleLastSeenBucket(t *testing.T) {
	privacy := newPresencePrivacyFake()
	privacy.lastSeen["alice"] = time.Now().UTC().Add(-90 * 24 * time.Hour)
	graph := &presenceGraphFake{
		contacts: map[string][]string{
			"alice": {"bob"},
			"bob":   {"alice"},
		},
	}
	store := NewMemoryStore(Config{OnlineTTL: time.Minute})
	svc := NewService(store, privacy, graph, &presencePublisherFake{}, Config{
		OnlineTTL:        time.Minute,
		CallTTL:          time.Minute,
		MaxUsersPerQuery: 50,
	})

	if err := svc.Heartbeat("alice", "phone"); err != nil {
		t.Fatalf("heartbeat: %v", err)
	}
	view, err := svc.ViewUser("bob", "alice")
	if err != nil {
		t.Fatalf("view online: %v", err)
	}
	if view.Status != model.PresenceOnline {
		t.Fatalf("status=%q, want online", view.Status)
	}
	if view.LastSeenBucket != "" {
		t.Fatalf("online view exposed stale last_seen_bucket=%q", view.LastSeenBucket)
	}
}

type presencePrivacyFake struct {
	settings map[string]model.PresencePrivacySettings
	lastSeen map[string]time.Time
}

func newPresencePrivacyFake() *presencePrivacyFake {
	return &presencePrivacyFake{
		settings: make(map[string]model.PresencePrivacySettings),
		lastSeen: make(map[string]time.Time),
	}
}

func (f *presencePrivacyFake) GetPresencePrivacy(userID string) (model.PresencePrivacySettings, error) {
	if settings, ok := f.settings[userID]; ok {
		return settings, nil
	}
	return model.PresencePrivacySettings{
		OnlineVisibility:   model.PresenceVisibilityContacts,
		LastSeenVisibility: model.PresenceVisibilityContacts,
	}, nil
}

func (f *presencePrivacyFake) SetPresencePrivacy(userID string, settings model.PresencePrivacySettings) error {
	f.settings[userID] = settings
	return nil
}

func (f *presencePrivacyFake) GetLastSeenAt(userID string) (*time.Time, error) {
	if at, ok := f.lastSeen[userID]; ok {
		return &at, nil
	}
	return nil, nil
}

func (f *presencePrivacyFake) SetLastSeenAt(userID string, at time.Time) error {
	f.lastSeen[userID] = at
	return nil
}

type presenceGraphFake struct {
	contacts map[string][]string
	members  map[string][]string
}

func (f *presenceGraphFake) ListContactUserIDs(userID string) ([]string, error) {
	return append([]string(nil), f.contacts[userID]...), nil
}

func (f *presenceGraphFake) GetChatMemberIDs(chatID, viewerID string) ([]string, error) {
	return append([]string(nil), f.members[chatID]...), nil
}

type presencePublisherFake struct {
	events []publishedPresenceEvent
}

type publishedPresenceEvent struct {
	recipients []string
	event      model.WSEvent
}

func (f *presencePublisherFake) BroadcastToUsers(memberIDs []string, event model.WSEvent) {
	f.events = append(f.events, publishedPresenceEvent{
		recipients: append([]string(nil), memberIDs...),
		event:      event,
	})
}

func (f *presencePublisherFake) requireEvent(t *testing.T, eventName string, recipients []string) {
	t.Helper()
	want := append([]string(nil), recipients...)
	sort.Strings(want)
	for _, event := range f.events {
		if event.event.Event != eventName {
			continue
		}
		got := append([]string(nil), event.recipients...)
		sort.Strings(got)
		if reflect.DeepEqual(got, want) {
			return
		}
	}
	t.Fatalf("event %q for %v not published; events=%+v", eventName, want, f.events)
}
