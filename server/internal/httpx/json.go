// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package httpx

import (
	"encoding/json"
	"net/http"
	"strconv"
)

func WriteJSON(w http.ResponseWriter, status int, payload any) {
	body, err := json.Marshal(payload)
	if err != nil {
		status = http.StatusInternalServerError
		body = []byte(`{"error":"json encode failed"}`)
	}
	body = append(body, '\n')

	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	// Discourage intermediary buffering of small JSON ACKs (call create 201).
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Length", strconv.Itoa(len(body)))
	w.WriteHeader(status)
	_, _ = w.Write(body)
	if flusher, ok := w.(http.Flusher); ok {
		flusher.Flush()
	}
}

func WriteError(w http.ResponseWriter, status int, message string) {
	WriteJSON(w, status, map[string]string{"error": message})
}
