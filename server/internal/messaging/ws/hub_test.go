// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package ws

import (
	"encoding/json"
	"testing"

	"glagolitsa/server/internal/model"
)

type testPresenceTracker struct {
	connected    int
	disconnected int
}

func (p *testPresenceTracker) OnSocketConnected(userID string) {
	p.connected++
}

func (p *testPresenceTracker) OnSocketDisconnected(userID string) {
	p.disconnected++
}

func TestHubUnregisterIsIdempotent(t *testing.T) {
	presence := &testPresenceTracker{}
	hub := NewHub(presence)
	c := &client{
		userID:   "user-1",
		deviceID: "device-1",
		send:     make(chan []byte, 1),
	}

	hub.register(c)
	hub.unregister(c)
	hub.unregister(c)

	if presence.connected != 1 {
		t.Fatalf("connected = %d, want 1", presence.connected)
	}
	if presence.disconnected != 1 {
		t.Fatalf("disconnected = %d, want 1", presence.disconnected)
	}
}

type testClusterPublisher struct {
	memberIDs []string
	event     model.WSEvent
	published int
}

func (c *testClusterPublisher) Publish(memberIDs []string, event model.WSEvent) {
	c.memberIDs = append([]string(nil), memberIDs...)
	c.event = event
	c.published++
}

func (c *testClusterPublisher) Close() error { return nil }

func TestHubBroadcastToUsersDeliversLocalAndCluster(t *testing.T) {
	hub := NewHub(nil)
	cluster := &testClusterPublisher{}
	hub.SetCluster(cluster)
	local := &client{
		userID:   "callee",
		deviceID: "phone",
		send:     make(chan []byte, 1),
	}
	hub.register(local)

	event := model.WSEvent{
		Event: "call.ringing",
		Data: map[string]string{
			"call_id": "call-1",
			"status":  "ringing",
		},
	}
	hub.BroadcastToUsers([]string{"callee"}, event)

	if cluster.published != 1 {
		t.Fatalf("cluster published %d times, want 1", cluster.published)
	}
	if len(cluster.memberIDs) != 1 || cluster.memberIDs[0] != "callee" {
		t.Fatalf("cluster recipients=%v", cluster.memberIDs)
	}
	select {
	case payload := <-local.send:
		var got model.WSEvent
		if err := json.Unmarshal(payload, &got); err != nil {
			t.Fatal(err)
		}
		if got.Event != event.Event {
			t.Fatalf("local event=%q want %q", got.Event, event.Event)
		}
	default:
		t.Fatal("local client did not receive event when cluster was configured")
	}
}
