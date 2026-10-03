// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strings"
)

func clientIPHash(r *http.Request) string {
	ip := r.RemoteAddr
	if forwarded := r.Header.Get("X-Forwarded-For"); forwarded != "" {
		parts := strings.Split(forwarded, ",")
		ip = strings.TrimSpace(parts[0])
	}
	if ip == "" {
		return ""
	}
	sum := sha256.Sum256([]byte("glagolitsa-ip:" + ip))
	return base64.RawURLEncoding.EncodeToString(sum[:8])
}

const maxAuthBodyBytes = 16 << 10 // 16 KiB

func decodeJSON(w http.ResponseWriter, r *http.Request, dst any) error {
	r.Body = http.MaxBytesReader(w, r.Body, maxAuthBodyBytes)
	return json.NewDecoder(r.Body).Decode(dst)
}
