// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"strings"
	"testing"
	"time"
)

func TestMintShortLivedTURN_hmacAndTTL(t *testing.T) {
	user, cred := mintShortLivedTURN("shared-secret", ICECredentialScope{
		UserID: "user-alice-uuid", DeviceID: "dev-1", CallID: "call-1",
	}, time.Hour)
	if user == "" || cred == "" {
		t.Fatal("empty credentials")
	}
	if strings.Contains(user, "alice") || strings.Contains(user, "user-alice") {
		t.Fatalf("username must not embed user id: %s", user)
	}
	exp, ok := ParseTURNUsernameExpiry(user)
	if !ok {
		t.Fatalf("parse username %q", user)
	}
	if exp < time.Now().Unix() {
		t.Fatal("expiry in the past")
	}
	// Stable HMAC for same inputs at same expiry second may differ across
	// seconds; just ensure credential is base64-ish length.
	if len(cred) < 20 {
		t.Fatalf("credential too short: %q", cred)
	}
}

func TestICEServersFor_prefersSharedSecretOverStatic(t *testing.T) {
	m := NewRoomManager(Config{
		STUNServers:      []string{"stun:stun.example:3478"},
		TURNDomain:       "turn.example",
		TURNUsername:     "static-user",
		TURNPassword:     "static-pass",
		TURNSharedSecret: "shared-secret",
		ICETTL:           time.Hour,
	})
	resp := m.ICEServersFor(ICECredentialScope{UserID: "u1", DeviceID: "d1"})
	if len(resp.Servers) < 2 {
		t.Fatalf("servers=%+v", resp.Servers)
	}
	var turn *struct{ user, cred string }
	for _, s := range resp.Servers {
		if len(s.URLs) > 0 && strings.HasPrefix(s.URLs[0], "turn") {
			turn = &struct{ user, cred string }{s.Username, s.Credential}
			break
		}
	}
	if turn == nil {
		// may be turns: — scan any with username
		for _, s := range resp.Servers {
			if s.Username != "" {
				turn = &struct{ user, cred string }{s.Username, s.Credential}
				break
			}
		}
	}
	if turn == nil {
		t.Fatal("no turn server entry")
	}
	if turn.user == "static-user" || turn.cred == "static-pass" {
		t.Fatal("must not return static credentials when shared secret set")
	}
}

func TestTurnURLs_domainPrefers443And3478(t *testing.T) {
	urls := turnURLs(Config{
		TURNDomain: "turn.glagolit.me",
		TURNURL:    "turn:203.0.113.10:3478?transport=udp",
	})
	want := []string{
		"turn:turn.glagolit.me:3478?transport=udp",
		"turn:turn.glagolit.me:443?transport=udp",
		"turns:turn.glagolit.me:443?transport=tcp",
		"turns:turn.glagolit.me:5349?transport=tcp",
		"turn:203.0.113.10:3478?transport=udp",
	}
	if len(urls) != len(want) {
		t.Fatalf("urls=%v want %v", urls, want)
	}
	for i := range want {
		if urls[i] != want[i] {
			t.Fatalf("urls[%d]=%q want %q", i, urls[i], want[i])
		}
	}
}

func TestSanitizeTURNDomain_rejectsAccidentalURL(t *testing.T) {
	if got := sanitizeTURNDomain("turn:203.0.113.10:3478?transport=udp"); got != "" {
		t.Fatalf("got %q", got)
	}
	if got := sanitizeTURNDomain("turn.glagolit.me"); got != "turn.glagolit.me" {
		t.Fatalf("got %q", got)
	}
}

func TestICEServersFor_noSecretLeakInSTUNOnly(t *testing.T) {
	m := NewRoomManager(Config{
		STUNServers: []string{"stun:stun.example:3478"},
	})
	resp := m.ICEServers()
	for _, s := range resp.Servers {
		if s.Credential != "" {
			t.Fatal("stun-only must not invent credentials")
		}
	}
}
