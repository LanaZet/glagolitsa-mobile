// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"net/http"
	"testing"

	"glagolitsa/server/internal/store"
)

func TestHelloIncludesStableServerID(t *testing.T) {
	server, mem := newFlowTestServer(t)
	expected, err := mem.GetServerID()
	if err != nil {
		t.Fatalf("GetServerID: %v", err)
	}
	if expected == "" {
		t.Fatal("GetServerID returned empty id")
	}

	type helloResponse struct {
		Message  string `json:"message"`
		ServerID string `json:"server_id"`
	}

	firstResp, err := http.Get(server.URL + "/api/hello")
	if err != nil {
		t.Fatalf("get first hello: %v", err)
	}
	var first helloResponse
	decodeJSON(t, firstResp, &first)

	secondResp, err := http.Get(server.URL + "/api/hello")
	if err != nil {
		t.Fatalf("get second hello: %v", err)
	}
	var second helloResponse
	decodeJSON(t, secondResp, &second)

	if first.ServerID != expected {
		t.Fatalf("first server_id=%q want %q", first.ServerID, expected)
	}
	if second.ServerID != expected {
		t.Fatalf("second server_id=%q want %q", second.ServerID, expected)
	}
	if first.Message == "" {
		t.Fatal("hello message must remain populated")
	}
}

func TestMemoryStoreServerIDIsPerInstance(t *testing.T) {
	first := store.NewMemory()
	second := store.NewMemory()

	firstID, err := first.GetServerID()
	if err != nil {
		t.Fatalf("first GetServerID: %v", err)
	}
	secondID, err := second.GetServerID()
	if err != nil {
		t.Fatalf("second GetServerID: %v", err)
	}

	if firstID == "" || secondID == "" {
		t.Fatalf("server IDs must be non-empty: first=%q second=%q", firstID, secondID)
	}
	if firstID == secondID {
		t.Fatalf("memory stores must not share server_id: %q", firstID)
	}
}
