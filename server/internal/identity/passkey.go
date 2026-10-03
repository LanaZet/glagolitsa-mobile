// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"encoding/json"
	"os"
	"strings"
	"time"

	"github.com/go-webauthn/webauthn/protocol"
	"github.com/go-webauthn/webauthn/webauthn"

	"glagolitsa/server/internal/model"
)

const (
	defaultPasskeyRPID      = "localhost"
	defaultPasskeyOrigin    = "http://localhost"
	defaultPasskeyTimeoutMs = 60_000
)

func newWebAuthn() (*webauthn.WebAuthn, string, error) {
	rpID := strings.TrimSpace(os.Getenv("WEBAUTHN_RP_ID"))
	if rpID == "" {
		rpID = defaultPasskeyRPID
	}
	origins := parseOrigins(os.Getenv("WEBAUTHN_ORIGINS"), os.Getenv("FRONTEND_ORIGIN"))
	display := strings.TrimSpace(os.Getenv("WEBAUTHN_RP_DISPLAY_NAME"))
	if display == "" {
		display = "Glagolitsa"
	}
	wa, err := webauthn.New(&webauthn.Config{
		RPDisplayName: display,
		RPID:          rpID,
		RPOrigins:     origins,
		Timeouts: webauthn.TimeoutsConfig{
			Login:        webauthn.TimeoutConfig{Timeout: time.Duration(defaultPasskeyTimeoutMs) * time.Millisecond},
			Registration: webauthn.TimeoutConfig{Timeout: time.Duration(defaultPasskeyTimeoutMs) * time.Millisecond},
		},
	})
	if err != nil {
		return nil, "", err
	}
	return wa, origins[0], nil
}

func parseOrigins(explicit, frontend string) []string {
	seen := map[string]struct{}{}
	var out []string
	add := func(raw string) {
		value := strings.TrimSpace(raw)
		if value == "" || value == "*" {
			return
		}
		if !strings.HasPrefix(value, "http://") && !strings.HasPrefix(value, "https://") {
			return
		}
		if _, ok := seen[value]; ok {
			return
		}
		seen[value] = struct{}{}
		out = append(out, value)
	}
	for _, part := range strings.Split(explicit, ",") {
		add(part)
	}
	add(frontend)
	if len(out) == 0 {
		out = []string{defaultPasskeyOrigin}
	}
	return out
}

type passkeyUser struct {
	id    []byte
	name  string
	creds []webauthn.Credential
}

func (u passkeyUser) WebAuthnID() []byte                         { return u.id }
func (u passkeyUser) WebAuthnName() string                       { return u.name }
func (u passkeyUser) WebAuthnDisplayName() string                { return u.name }
func (u passkeyUser) WebAuthnCredentials() []webauthn.Credential { return u.creds }

func passkeyUserID(accountID string) []byte {
	return []byte(accountID)
}

func credentialFromRecord(record model.WebAuthnCredentialRecord) webauthn.Credential {
	var cred webauthn.Credential
	if len(record.CredentialJSON) > 0 && string(record.CredentialJSON) != "null" {
		if err := json.Unmarshal(record.CredentialJSON, &cred); err == nil && len(cred.ID) > 0 {
			return cred
		}
	}
	return webauthn.Credential{
		ID:              record.CredentialID,
		PublicKey:       record.PublicKey,
		AttestationType: record.AttestationType,
		Authenticator: webauthn.Authenticator{
			SignCount: record.SignCount,
		},
	}
}

func recordFromCredential(userID, deviceID string, cred *webauthn.Credential) (model.WebAuthnCredentialRecord, error) {
	raw, err := json.Marshal(cred)
	if err != nil {
		return model.WebAuthnCredentialRecord{}, err
	}
	return model.WebAuthnCredentialRecord{
		UserID:          userID,
		DeviceID:        deviceID,
		CredentialID:    cred.ID,
		PublicKey:       cred.PublicKey,
		SignCount:       cred.Authenticator.SignCount,
		AttestationType: cred.AttestationType,
		CredentialJSON:  raw,
	}, nil
}

func encodedIDs(ids [][]byte) []string {
	out := make([]string, 0, len(ids))
	for _, id := range ids {
		out = append(out, protocol.URLEncodedBase64(id).String())
	}
	return out
}
