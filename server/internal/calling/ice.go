// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package calling

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"fmt"
	"strconv"
	"strings"
	"time"

	"glagolitsa/server/internal/model"
)

// Default short-lived ICE credential lifetime for public builds.
// Long enough for call setup + reconnect; refresh via /api/ice/servers.
const DefaultShortLivedICETTL = time.Hour

// ICEServers returns STUN/TURN config. Prefer short-lived TURN credentials
// (TURN_SHARED_SECRET) over static username/password. Never log the result.
func (m *RoomManager) ICEServers() model.ICEServersResponse {
	return m.ICEServersFor(ICECredentialScope{})
}

// ICECredentialScope optionally scopes short-lived credentials to a session.
// Scope values appear only inside the TURN username (not as secrets).
type ICECredentialScope struct {
	UserID   string
	DeviceID string
	CallID   string
}

func (m *RoomManager) ICEServersFor(scope ICECredentialScope) model.ICEServersResponse {
	servers := make([]model.ICEServer, 0, 4)
	if len(m.cfg.STUNServers) > 0 {
		servers = append(servers, model.ICEServer{URLs: m.cfg.STUNServers})
	}

	ttl := m.cfg.ICETTL
	if ttl <= 0 {
		ttl = DefaultShortLivedICETTL
	}
	// Cap public static-credential path; short-lived uses Default when long.
	if m.cfg.TURNSharedSecret != "" && ttl > 6*time.Hour {
		ttl = DefaultShortLivedICETTL
	}

	urls := turnURLs(m.cfg)
	if len(urls) == 0 {
		return model.ICEServersResponse{Servers: servers, TTL: int(ttl.Seconds())}
	}

	username, credential := m.turnCredentials(scope, ttl)
	if username != "" || credential != "" {
		servers = append(servers, model.ICEServer{
			URLs:       urls,
			Username:   username,
			Credential: credential,
		})
	}

	return model.ICEServersResponse{
		Servers: servers,
		TTL:     int(ttl.Seconds()),
	}
}

func turnURLs(cfg Config) []string {
	urls := make([]string, 0, 6)
	domain := sanitizeTURNDomain(cfg.TURNDomain)
	if domain != "" {
		for _, c := range []string{
			"turn:" + domain + ":3478?transport=udp",
			"turn:" + domain + ":443?transport=udp",
			"turns:" + domain + ":443?transport=tcp",
			"turns:" + domain + ":5349?transport=tcp",
		} {
			urls = append(urls, c)
		}
	}
	if u := strings.TrimSpace(cfg.TURNURL); u != "" && !containsURL(urls, u) {
		urls = append(urls, u)
	}
	return urls
}

func sanitizeTURNDomain(raw string) string {
	domain := strings.TrimSpace(raw)
	if domain == "" {
		return ""
	}
	lower := strings.ToLower(domain)
	if strings.Contains(domain, "://") ||
		strings.HasPrefix(lower, "turn:") ||
		strings.HasPrefix(lower, "turns:") ||
		strings.HasPrefix(lower, "stun:") {
		return ""
	}
	if i := strings.IndexByte(domain, '?'); i >= 0 {
		domain = domain[:i]
	}
	return strings.TrimSpace(domain)
}

func containsURL(urls []string, want string) bool {
	for _, u := range urls {
		if u == want {
			return true
		}
	}
	return false
}

func (m *RoomManager) turnCredentials(scope ICECredentialScope, ttl time.Duration) (username, credential string) {
	if secret := strings.TrimSpace(m.cfg.TURNSharedSecret); secret != "" {
		return mintShortLivedTURN(secret, scope, ttl)
	}
	// Dev-only static path — production should set TURN_SHARED_SECRET.
	return m.cfg.TURNUsername, m.cfg.TURNPassword
}

// mintShortLivedTURN implements coturn static-auth-secret REST credentials:
// username = <expiryUnix>:<opaque>, password = base64(hmac-sha1(secret, username)).
func mintShortLivedTURN(secret string, scope ICECredentialScope, ttl time.Duration) (string, string) {
	if ttl <= 0 {
		ttl = DefaultShortLivedICETTL
	}
	expiry := time.Now().UTC().Add(ttl).Unix()
	opaque := "u"
	if scope.UserID != "" {
		// Opaque fragment: truncated HMAC so TURN username does not embed raw user id.
		opaque = OpaqueParticipantID(secret, scope.CallID, scope.UserID, scope.DeviceID)
		if len(opaque) > 20 {
			opaque = opaque[:20]
		}
	}
	username := fmt.Sprintf("%d:%s", expiry, opaque)
	mac := hmac.New(sha1.New, []byte(secret))
	_, _ = mac.Write([]byte(username))
	credential := base64.StdEncoding.EncodeToString(mac.Sum(nil))
	return username, credential
}

// ParseTURNUsernameExpiry returns unix expiry from short-lived username, if any.
func ParseTURNUsernameExpiry(username string) (int64, bool) {
	parts := strings.SplitN(username, ":", 2)
	if len(parts) != 2 {
		return 0, false
	}
	exp, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		return 0, false
	}
	return exp, true
}
