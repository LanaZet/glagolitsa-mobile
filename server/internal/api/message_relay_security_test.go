// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"net/http"
	"testing"
	"time"

	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/relayauth"
	"glagolitsa/server/internal/store"
)

// Signal-style: opaque relay, mailbox routing, no server-side decryption.

func TestMessageRelaySecurity_unknownMailboxNotFound(t *testing.T) {
	server, _ := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	resp := postJSONWithHeaders(t, server.URL+"/api/messages/relay", model.RelayMessageRequest{
		Envelopes: []model.RelayEnvelopeRequest{{
			MailboxToken:  "00000000-0000-0000-0000-000000000099",
			DeliveryToken: relayauth.DeliveryTokenForMailbox("00000000-0000-0000-0000-000000000099"),
			EnvelopeType:  3,
			Ciphertext:    fakeCiphertextB64(),
		}},
	}, peers.aliceToken, peers.aliceDevice)
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestMessageRelaySecurity_invalidDeliveryTokenForbidden(t *testing.T) {
	server, _ := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	resp := postJSONWithHeaders(t, server.URL+"/api/messages/relay", model.RelayMessageRequest{
		Envelopes: []model.RelayEnvelopeRequest{{
			MailboxToken:  peers.bobMailbox,
			DeliveryToken: "invalid-delivery-token",
			EnvelopeType:  3,
			Ciphertext:    fakeCiphertextB64(),
		}},
	}, peers.aliceToken, peers.aliceDevice)
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestMessageRelaySecurity_tooManyEnvelopesPerRequest(t *testing.T) {
	t.Setenv("RELAY_MAX_ENVELOPES_PER_REQUEST", "50")
	server, _ := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	envelopes := make([]model.RelayEnvelopeRequest, 51)
	for i := range envelopes {
		envelopes[i] = model.RelayEnvelopeRequest{
			MailboxToken:  peers.bobMailbox,
			DeliveryToken: peers.bobDeliveryToken,
			EnvelopeType:  3,
			Ciphertext:    fakeCiphertextB64(),
		}
	}
	resp := postJSONWithHeaders(t, server.URL+"/api/messages/relay", model.RelayMessageRequest{
		Envelopes: envelopes,
	}, peers.aliceToken, peers.aliceDevice)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestMessageRelaySecurity_dailyRelayQuotaExceeded(t *testing.T) {
	t.Setenv("RELAY_LIMIT_NEW_TIER", "200")
	server, mem := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	claims, err := auth.ParseToken(peers.aliceToken)
	if err != nil {
		t.Fatalf("parse token: %v", err)
	}
	if err := mem.IncrementRelayEnvelopeCount(claims.UserID, 200); err != nil {
		t.Fatalf("seed quota: %v", err)
	}

	resp := postJSONWithHeaders(t, server.URL+"/api/messages/relay", model.RelayMessageRequest{
		Envelopes: []model.RelayEnvelopeRequest{{
			MailboxToken:  peers.bobMailbox,
			DeliveryToken: peers.bobDeliveryToken,
			EnvelopeType:  3,
			Ciphertext:    fakeCiphertextB64(),
		}},
	}, peers.aliceToken, peers.aliceDevice)
	if resp.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("status = %d, want 429", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestMessageRelaySecurity_standardEnvelopeType1(t *testing.T) {
	server, _ := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	relayResp := postJSONWithHeaders(t, server.URL+"/api/messages/relay", model.RelayMessageRequest{
		Envelopes: []model.RelayEnvelopeRequest{{
			MailboxToken:  peers.bobMailbox,
			DeliveryToken: peers.bobDeliveryToken,
			EnvelopeType:  1,
			Ciphertext:    fakeCiphertextB64(),
		}},
	}, peers.aliceToken, peers.aliceDevice)
	if relayResp.StatusCode != http.StatusCreated {
		t.Fatalf("relay status = %d, want 201", relayResp.StatusCode)
	}
	var relay model.RelayMessageResponse
	decodeJSON(t, relayResp, &relay)
	if relay.Enqueued != 1 {
		t.Fatalf("enqueued = %d", relay.Enqueued)
	}
}

func TestMessageRelaySecurity_multiEnvelopeBatch(t *testing.T) {
	server, _ := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	relayResp := postJSONWithHeaders(t, server.URL+"/api/messages/relay", model.RelayMessageRequest{
		Envelopes: []model.RelayEnvelopeRequest{
			{MailboxToken: peers.bobMailbox, DeliveryToken: peers.bobDeliveryToken, EnvelopeType: 3, Ciphertext: fakeCiphertextB64()},
			{MailboxToken: peers.bobMailbox, DeliveryToken: peers.bobDeliveryToken, EnvelopeType: 3, Ciphertext: fakeCiphertextB64()},
		},
	}, peers.aliceToken, peers.aliceDevice)
	if relayResp.StatusCode != http.StatusCreated {
		t.Fatalf("relay status = %d", relayResp.StatusCode)
	}
	var relay model.RelayMessageResponse
	decodeJSON(t, relayResp, &relay)
	if relay.Enqueued != 2 || len(relay.IDs) != 2 {
		t.Fatalf("unexpected batch response: %+v", relay)
	}
}

func TestMessageRelaySecurity_sendAliasDelegatesToRelay(t *testing.T) {
	server, _ := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	resp := postJSONWithHeaders(t, server.URL+"/api/messages/send", model.RelayMessageRequest{
		Envelopes: []model.RelayEnvelopeRequest{{
			MailboxToken:  peers.bobMailbox,
			DeliveryToken: peers.bobDeliveryToken,
			EnvelopeType:  3,
			Ciphertext:    fakeCiphertextB64(),
		}},
	}, peers.aliceToken, peers.aliceDevice)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("send alias status = %d, want 201", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestMessageRelaySecurity_ackEmptyEnvelopeIdsRejected(t *testing.T) {
	server, _ := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	resp := postJSONWithHeaders(t, server.URL+"/api/messages/queue/ack", model.AckMessageQueueRequest{
		EnvelopeIDs: nil,
	}, peers.bobToken, peers.bobDevice)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestMessageRelaySecurity_ackForeignEnvelopeDeletesNothing(t *testing.T) {
	server, _ := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	relayResp := postJSONWithHeaders(t, server.URL+"/api/messages/relay", model.RelayMessageRequest{
		Envelopes: []model.RelayEnvelopeRequest{{
			MailboxToken:  peers.bobMailbox,
			DeliveryToken: peers.bobDeliveryToken,
			EnvelopeType:  3,
			Ciphertext:    fakeCiphertextB64(),
		}},
	}, peers.aliceToken, peers.aliceDevice)
	var relay model.RelayMessageResponse
	decodeJSON(t, relayResp, &relay)
	envelopeID := relay.IDs[0]

	ackResp := postJSONWithHeaders(t, server.URL+"/api/messages/queue/ack", model.AckMessageQueueRequest{
		EnvelopeIDs: []string{"00000000-0000-0000-0000-000000000099"},
	}, peers.bobToken, peers.bobDevice)
	if ackResp.StatusCode != http.StatusOK {
		t.Fatalf("ack status = %d", ackResp.StatusCode)
	}
	var ack model.AckMessageQueueResponse
	decodeJSON(t, ackResp, &ack)
	if ack.Deleted != 0 {
		t.Fatalf("deleted = %d, want 0", ack.Deleted)
	}

	queueResp := getAuthWithHeaders(t, server.URL+"/api/messages/queue?limit=10", peers.bobToken, peers.bobDevice)
	var queue model.MessageQueueResponse
	decodeJSON(t, queueResp, &queue)
	found := false
	for _, item := range queue.Envelopes {
		if item.EnvelopeID == envelopeID {
			found = true
		}
	}
	if !found {
		t.Fatalf("envelope %q removed by foreign ack", envelopeID)
	}
}

func TestMessageRelaySecurity_queueWrongDeviceNotFound(t *testing.T) {
	server, _ := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	resp := getAuthWithHeaders(t, server.URL+"/api/messages/queue?limit=10", peers.bobToken, "nonexistent-device-id")
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestMessageRelaySecurity_mailboxRotationGracePeriod(t *testing.T) {
	server, _ := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	rotateResp := postJSONWithHeaders(t, server.URL+"/api/devices/"+peers.bobDevice+"/mailbox/rotate", struct{}{}, peers.bobToken, peers.bobDevice)
	if rotateResp.StatusCode != http.StatusOK {
		t.Fatalf("rotate status = %d", rotateResp.StatusCode)
	}
	var rotated model.RotateMailboxResponse
	decodeJSON(t, rotateResp, &rotated)
	if rotated.PreviousMailboxToken == "" {
		t.Fatal("expected previous mailbox token after rotation")
	}

	relayResp := postJSONWithHeaders(t, server.URL+"/api/messages/relay", model.RelayMessageRequest{
		Envelopes: []model.RelayEnvelopeRequest{{
			MailboxToken:  rotated.PreviousMailboxToken,
			DeliveryToken: relayauth.DeliveryTokenForMailbox(rotated.PreviousMailboxToken),
			EnvelopeType:  3,
			Ciphertext:    fakeCiphertextB64(),
		}},
	}, peers.aliceToken, peers.aliceDevice)
	if relayResp.StatusCode != http.StatusCreated {
		t.Fatalf("relay to previous mailbox status = %d, want 201", relayResp.StatusCode)
	}

	queueResp := getAuthWithHeaders(t, server.URL+"/api/messages/queue?limit=10", peers.bobToken, peers.bobDevice)
	var queue model.MessageQueueResponse
	decodeJSON(t, queueResp, &queue)
	if len(queue.Envelopes) == 0 {
		t.Fatal("expected envelope on rotated mailbox grace token")
	}
}

func TestMessageRelaySecurity_customExpiresAtSec(t *testing.T) {
	server, mem := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)
	shortTTL := int64(2)

	relayResp := postJSONWithHeaders(t, server.URL+"/api/messages/relay", model.RelayMessageRequest{
		ExpiresAtSec: &shortTTL,
		Envelopes: []model.RelayEnvelopeRequest{{
			MailboxToken:  peers.bobMailbox,
			DeliveryToken: peers.bobDeliveryToken,
			EnvelopeType:  3,
			Ciphertext:    fakeCiphertextB64(),
		}},
	}, peers.aliceToken, peers.aliceDevice)
	if relayResp.StatusCode != http.StatusCreated {
		t.Fatalf("relay status = %d", relayResp.StatusCode)
	}
	var relay model.RelayMessageResponse
	decodeJSON(t, relayResp, &relay)

	tokens, err := mem.ResolveMailboxTokens(peers.bobDevice, peers.bobUserID)
	if err != nil {
		t.Fatalf("resolve tokens: %v", err)
	}
	records, err := mem.ListQueuedEnvelopes(tokens, 10)
	if err != nil {
		t.Fatalf("list queued: %v", err)
	}
	if len(records) != 1 {
		t.Fatalf("queued count = %d", len(records))
	}
	if records[0].ExpiresAt.Sub(store.NowUTC()) > 3*time.Second {
		t.Fatalf("expires_at too far in future: %v", records[0].ExpiresAt)
	}
	_ = relay
}

func TestMessageRelaySecurity_revokedSessionCannotRelay(t *testing.T) {
	server, mem := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	claims, err := auth.ParseToken(peers.aliceToken)
	if err != nil {
		t.Fatalf("parse token: %v", err)
	}
	if err := mem.RevokeSession(claims.SessionID); err != nil {
		t.Fatalf("revoke session: %v", err)
	}

	resp := postJSONWithHeaders(t, server.URL+"/api/messages/relay", model.RelayMessageRequest{
		Envelopes: []model.RelayEnvelopeRequest{{
			MailboxToken:  peers.bobMailbox,
			DeliveryToken: peers.bobDeliveryToken,
			EnvelopeType:  3,
			Ciphertext:    fakeCiphertextB64(),
		}},
	}, peers.aliceToken, peers.aliceDevice)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("revoked session relay status = %d, want 401", resp.StatusCode)
	}
	resp.Body.Close()
}
