// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package httpx

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestWriteJSONCompletesBodyBeforeHandlerReturns(t *testing.T) {
	releaseHandler := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		WriteJSON(w, http.StatusOK, map[string]string{"status": "ok"})
		<-releaseHandler
	}))
	defer func() {
		close(releaseHandler)
		server.Close()
	}()

	client := http.Client{Timeout: time.Second}
	resp, err := client.Get(server.URL)
	if err != nil {
		t.Fatalf("GET failed before handler returned: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want %d", resp.StatusCode, http.StatusOK)
	}
	if resp.ContentLength <= 0 {
		t.Fatalf("ContentLength = %d, want positive value", resp.ContentLength)
	}
	if len(resp.TransferEncoding) != 0 {
		t.Fatalf("TransferEncoding = %v, want none", resp.TransferEncoding)
	}

	var body map[string]string
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if body["status"] != "ok" {
		t.Fatalf("body status = %q, want ok", body["status"])
	}
}
