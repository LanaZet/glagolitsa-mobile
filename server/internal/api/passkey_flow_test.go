// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"encoding/asn1"
	"encoding/base64"
	"encoding/json"
	"math/big"
	"net/http"
	"testing"

	"glagolitsa/server/internal/model"
)

func TestPasskey_registerLoginAndRecoverPassword(t *testing.T) {
	server, _ := newFlowTestServer(t)
	username := uniqueName("passkey")
	token, userID := registerUser(t, server.URL, username)
	registerDevice(t, server.URL, token, "dev-"+userID[:8])

	begin := postJSON(t, server.URL+"/api/auth/webauthn/register/begin", map[string]any{}, token)
	if begin.StatusCode != http.StatusOK {
		t.Fatalf("register begin status = %d", begin.StatusCode)
	}
	var creation model.WebAuthnBeginResponse
	decodeJSON(t, begin, &creation)

	passkey := newSoftPasskey(t)
	attestation := passkey.attest(t, creation.Challenge, creation.RPID, creation.Origin, creation.UserID)
	finish := postJSON(t, server.URL+"/api/auth/webauthn/register/finish", model.WebAuthnFinishRequest{
		SessionID:  creation.SessionID,
		Credential: mustJSON(t, attestation),
	}, token)
	if finish.StatusCode != http.StatusOK {
		t.Fatalf("register finish status = %d", finish.StatusCode)
	}
	finish.Body.Close()

	status := getAuth(t, server.URL+"/api/recovery/status", token)
	if status.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", status.StatusCode)
	}
	var ready model.RecoveryStatusResponse
	decodeJSON(t, status, &ready)
	if !ready.PasskeyReady {
		t.Fatal("expected passkey_ready")
	}

	loginBegin := postJSON(t, server.URL+"/api/auth/webauthn/login/begin", model.WebAuthnBeginRequest{
		Username: username,
	}, "")
	if loginBegin.StatusCode != http.StatusOK {
		t.Fatalf("login begin status = %d", loginBegin.StatusCode)
	}
	var loginOpts model.WebAuthnBeginResponse
	decodeJSON(t, loginBegin, &loginOpts)
	assertion := passkey.assert(t, loginOpts.Challenge, loginOpts.RPID, loginOpts.Origin)
	loginFinish := postJSON(t, server.URL+"/api/auth/webauthn/login/finish", model.WebAuthnFinishRequest{
		SessionID:  loginOpts.SessionID,
		Username:   username,
		Credential: mustJSON(t, assertion),
	}, "")
	if loginFinish.StatusCode != http.StatusOK {
		t.Fatalf("login finish status = %d", loginFinish.StatusCode)
	}
	var loggedIn model.AuthResponse
	decodeJSON(t, loginFinish, &loggedIn)
	if loggedIn.Token == "" || loggedIn.User.Username != username {
		t.Fatalf("unexpected login: %+v", loggedIn)
	}

	recoverBegin := postJSON(t, server.URL+"/api/recovery/passkey/begin", model.WebAuthnBeginRequest{
		Username: username,
	}, "")
	if recoverBegin.StatusCode != http.StatusOK {
		t.Fatalf("recovery begin status = %d", recoverBegin.StatusCode)
	}
	var recoverOpts model.WebAuthnBeginResponse
	decodeJSON(t, recoverBegin, &recoverOpts)
	recoverAssert := passkey.assert(t, recoverOpts.Challenge, recoverOpts.RPID, recoverOpts.Origin)
	recoverFinish := postJSON(t, server.URL+"/api/recovery/passkey/finish", model.WebAuthnFinishRequest{
		SessionID:  recoverOpts.SessionID,
		Username:   username,
		Credential: mustJSON(t, recoverAssert),
	}, "")
	if recoverFinish.StatusCode != http.StatusOK {
		t.Fatalf("recovery finish status = %d", recoverFinish.StatusCode)
	}
	var ticket model.RecoveryTicketResponse
	decodeJSON(t, recoverFinish, &ticket)
	if ticket.RecoveryToken == "" {
		t.Fatal("missing recovery token")
	}

	complete := postJSON(t, server.URL+"/api/recovery/complete", model.RecoveryCompleteRequest{
		RecoveryToken: ticket.RecoveryToken,
		NewPassword:   "passkey-new-99",
	}, "")
	if complete.StatusCode != http.StatusOK {
		t.Fatalf("complete status = %d", complete.StatusCode)
	}
	complete.Body.Close()

	login := postJSON(t, server.URL+"/api/auth/login", model.LoginRequest{
		Username: username,
		Password: "passkey-new-99",
	}, "")
	if login.StatusCode != http.StatusOK {
		t.Fatalf("password after passkey recovery status = %d", login.StatusCode)
	}
	login.Body.Close()
}

func TestPasskey_unknownUserDoesNotEnumerate(t *testing.T) {
	server, _ := newFlowTestServer(t)
	resp := postJSON(t, server.URL+"/api/auth/webauthn/login/begin", model.WebAuthnBeginRequest{
		Username: "nobody-passkey",
	}, "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	var began model.WebAuthnBeginResponse
	decodeJSON(t, resp, &began)
	if began.SessionID == "" || began.Challenge == "" {
		t.Fatalf("dummy ceremony missing: %+v", began)
	}
}

type softPasskey struct {
	priv      *ecdsa.PrivateKey
	credID    []byte
	signCount uint32
}

func newSoftPasskey(t *testing.T) *softPasskey {
	t.Helper()
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	credID := make([]byte, 32)
	if _, err := rand.Read(credID); err != nil {
		t.Fatal(err)
	}
	return &softPasskey{priv: priv, credID: credID, signCount: 1}
}

func (s *softPasskey) attest(t *testing.T, challenge, rpID, origin, userID string) map[string]any {
	t.Helper()
	clientData := clientDataJSON("webauthn.create", challenge, origin)
	authData := s.authenticatorData(rpID, true)
	attestation := cborMap([]cborPair{
		{cborText("fmt"), cborText("none")},
		{cborText("attStmt"), cborMap(nil)},
		{cborText("authData"), cborBytes(authData)},
	})
	id := b64url(s.credID)
	return map[string]any{
		"id":    id,
		"rawId": id,
		"type":  "public-key",
		"response": map[string]any{
			"clientDataJSON":    b64url(clientData),
			"attestationObject": b64url(attestation),
		},
	}
}

func (s *softPasskey) assert(t *testing.T, challenge, rpID, origin string) map[string]any {
	t.Helper()
	s.signCount++
	clientData := clientDataJSON("webauthn.get", challenge, origin)
	authData := s.authenticatorData(rpID, false)
	sum := sha256.Sum256(clientData)
	toSign := append(append([]byte{}, authData...), sum[:]...)
	sig := ecdsaSign(t, s.priv, toSign)
	id := b64url(s.credID)
	return map[string]any{
		"id":    id,
		"rawId": id,
		"type":  "public-key",
		"response": map[string]any{
			"clientDataJSON":    b64url(clientData),
			"authenticatorData": b64url(authData),
			"signature":         b64url(sig),
			"userHandle":        "",
		},
	}
}

func (s *softPasskey) authenticatorData(rpID string, attested bool) []byte {
	rpHash := sha256.Sum256([]byte(rpID))
	flags := byte(0x05) // UP | UV
	if attested {
		flags |= 0x40 // AT
	}
	out := append(rpHash[:], flags)
	out = append(out, uint32Bytes(s.signCount)...)
	if !attested {
		return out
	}
	out = append(out, make([]byte, 16)...) // AAGUID
	out = append(out, uint16Bytes(len(s.credID))...)
	out = append(out, s.credID...)
	out = append(out, coseEC2Key(s.priv.X, s.priv.Y)...)
	return out
}

func clientDataJSON(typ, challenge, origin string) []byte {
	raw, _ := json.Marshal(map[string]any{
		"type":        typ,
		"challenge":   challenge,
		"origin":      origin,
		"crossOrigin": false,
	})
	return raw
}

func ecdsaSign(t *testing.T, priv *ecdsa.PrivateKey, data []byte) []byte {
	t.Helper()
	digest := sha256.Sum256(data)
	r, s, err := ecdsa.Sign(rand.Reader, priv, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	der, err := asn1.Marshal(struct {
		R, S *big.Int
	}{R: r, S: s})
	if err != nil {
		t.Fatal(err)
	}
	return der
}

func coseEC2Key(x, y *big.Int) []byte {
	return cborMap([]cborPair{
		{cborUnsigned(1), cborUnsigned(2)},
		{cborUnsigned(3), cborNegative(-7)},
		{cborNegative(-1), cborUnsigned(1)},
		{cborNegative(-2), cborBytes(pad32(x.Bytes()))},
		{cborNegative(-3), cborBytes(pad32(y.Bytes()))},
	})
}

func pad32(raw []byte) []byte {
	if len(raw) >= 32 {
		return raw[len(raw)-32:]
	}
	out := make([]byte, 32)
	copy(out[32-len(raw):], raw)
	return out
}

func uint32Bytes(v uint32) []byte {
	return []byte{byte(v >> 24), byte(v >> 16), byte(v >> 8), byte(v)}
}

func uint16Bytes(v int) []byte {
	return []byte{byte(v >> 8), byte(v)}
}

func b64url(raw []byte) string {
	return base64.RawURLEncoding.EncodeToString(raw)
}

func mustJSON(t *testing.T, value any) json.RawMessage {
	t.Helper()
	raw, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

type cborPair struct {
	key, value []byte
}

func cborMap(pairs []cborPair) []byte {
	out := cborHead(5, uint64(len(pairs)))
	for _, pair := range pairs {
		out = append(out, pair.key...)
		out = append(out, pair.value...)
	}
	return out
}

func cborText(value string) []byte {
	raw := []byte(value)
	return append(cborHead(3, uint64(len(raw))), raw...)
}

func cborBytes(value []byte) []byte {
	return append(cborHead(2, uint64(len(value))), value...)
}

func cborUnsigned(value uint64) []byte {
	return cborHead(0, value)
}

func cborNegative(value int64) []byte {
	if value >= 0 {
		return cborUnsigned(uint64(value))
	}
	return cborHead(1, uint64(-value-1))
}

func cborHead(major byte, n uint64) []byte {
	if n < 24 {
		return []byte{(major << 5) | byte(n)}
	}
	if n < 256 {
		return []byte{(major << 5) | 24, byte(n)}
	}
	return []byte{(major << 5) | 25, byte(n >> 8), byte(n)}
}
