// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package messaging

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/hooks"
	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/relayauth"
	"glagolitsa/server/internal/store"
)

type blockingRelayHook struct {
	hooks.BaseRelayHook
	started chan struct{}
	release chan struct{}
}

func (h blockingRelayHook) EnvelopesRelayed(ctx context.Context, actorID string, envelopeIDs []string) error {
	close(h.started)
	select {
	case <-h.release:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func TestRelayMessageRespondsBeforeAfterAcceptHooks(t *testing.T) {
	mem := store.NewMemory()
	mailbox := registerMessagingTestDevice(t, mem, "recipient", "recipient-device")
	registerMessagingTestDevice(t, mem, "sender", "sender-device")

	h := NewHandler(mem, mem, nil, nil, nil)
	hook := blockingRelayHook{
		started: make(chan struct{}),
		release: make(chan struct{}),
	}
	h.Hooks.AddRelayHook(hook)
	defer close(hook.release)

	token, _, err := auth.IssueAccessToken(auth.AccessTokenInput{
		UserID:    "sender",
		Username:  "sender",
		DeviceID:  "sender-device",
		TrustTier: "trusted",
	})
	if err != nil {
		t.Fatalf("issue token: %v", err)
	}

	body, err := json.Marshal(model.RelayMessageRequest{
		ClientMessageID: "pending-regression",
		Envelopes: []model.RelayEnvelopeRequest{{
			MailboxToken:  mailbox,
			DeliveryToken: relayauth.DeliveryTokenForMailbox(mailbox),
			EnvelopeType:  3,
			Ciphertext:    fakeMessagingCiphertext(),
		}},
	})
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	req := httptest.NewRequest(http.MethodPost, "/api/messages/relay", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
	rec := httptest.NewRecorder()

	done := make(chan struct{})
	start := time.Now()
	go func() {
		httpx.WithAuth(h.RelayMessage)(rec, req)
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(200 * time.Millisecond):
		t.Fatal("relay response waited for after-accept hook")
	}
	if elapsed := time.Since(start); elapsed > 200*time.Millisecond {
		t.Fatalf("relay response took %s", elapsed)
	}
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body=%s", rec.Code, rec.Body.String())
	}

	select {
	case <-hook.started:
	case <-time.After(time.Second):
		t.Fatal("after-accept hook did not start")
	}
}

func registerMessagingTestDevice(t *testing.T, mem *store.MemoryStore, accountID, deviceID string) string {
	t.Helper()
	_, err := mem.RegisterDevice(store.DeviceRegistration{
		AccountID:         accountID,
		DeviceID:          deviceID,
		RegistrationID:    42,
		IdentityPublicKey: bytes.Repeat([]byte{7}, 32),
		SignedPreKey: store.SignedPreKeyRecord{
			ID:        1,
			PublicKey: bytes.Repeat([]byte{1}, 32),
			Signature: bytes.Repeat([]byte{2}, 64),
		},
		PqPreKey: store.PqPreKeyRecord{
			ID:             1,
			PublicMaterial: bytes.Repeat([]byte{3}, 32),
			Signature:      bytes.Repeat([]byte{4}, 64),
		},
		OneTimePrekeys: []store.OneTimePreKeyRecord{{
			ID:        301,
			PublicKey: bytes.Repeat([]byte{5}, 32),
		}},
	})
	if err != nil {
		t.Fatalf("register device: %v", err)
	}
	token, err := mem.GetDeviceMailboxToken(deviceID, accountID)
	if err != nil {
		t.Fatalf("mailbox token: %v", err)
	}
	return token
}

func fakeMessagingCiphertext() string {
	return "ZmFrZS1jaXBoZXJ0ZXh0LWJ5dGVz"
}
