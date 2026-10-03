// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"encoding/base64"
	"fmt"
	"net/http"
	"testing"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

// Signal-style multi-device fan-out: one relay request can enqueue envelopes
// to several recipient devices; each device only sees its own mailbox queue.
func TestMessageRelayFlow_fanoutToMultipleRecipientDevices(t *testing.T) {
	server, _ := newFlowTestServer(t)
	suffix := fmt.Sprintf("%d", store.NowUTC().UnixNano())

	aliceToken, _ := registerUser(t, server.URL, alphaTestUsername("mdalice"))
	bobToken, bobUserID := registerUser(t, server.URL, alphaTestUsername("mdbob"))

	aliceDevice := "alice-device-" + suffix
	bobDevice1 := "bob-device-1-" + suffix
	bobDevice2 := "bob-device-2-" + suffix
	registerDevice(t, server.URL, aliceToken, aliceDevice)
	registerDevice(t, server.URL, bobToken, bobDevice1)
	// Second device starts pending unless confirmed by an active device (Signal-like linking).
	registerConfirmedDevice(t, server.URL, bobToken, bobDevice2, bobDevice1)

	bobDeviceInfo1 := lookupUserDevice(t, server.URL, aliceToken, bobUserID, bobDevice1)
	bobDeviceInfo2 := lookupUserDevice(t, server.URL, aliceToken, bobUserID, bobDevice2)
	bobMailbox1 := bobDeviceInfo1.MailboxToken
	bobMailbox2 := bobDeviceInfo2.MailboxToken
	if bobMailbox1 == bobMailbox2 {
		t.Fatalf("expected distinct mailboxes, both %q", bobMailbox1)
	}

	ct1 := base64.StdEncoding.EncodeToString([]byte("ciphertext-device-1-" + suffix))
	ct2 := base64.StdEncoding.EncodeToString([]byte("ciphertext-device-2-" + suffix))

	relayResp := postJSONWithHeaders(t, server.URL+"/api/messages/relay", model.RelayMessageRequest{
		ClientMessageID: "pending-md-1",
		Envelopes: []model.RelayEnvelopeRequest{
			{MailboxToken: bobMailbox1, DeliveryToken: bobDeviceInfo1.DeliveryToken, EnvelopeType: 3, Ciphertext: ct1},
			{MailboxToken: bobMailbox2, DeliveryToken: bobDeviceInfo2.DeliveryToken, EnvelopeType: 3, Ciphertext: ct2},
		},
	}, aliceToken, aliceDevice)
	if relayResp.StatusCode != http.StatusCreated {
		t.Fatalf("relay status = %d, want 201", relayResp.StatusCode)
	}
	var relay model.RelayMessageResponse
	decodeJSON(t, relayResp, &relay)
	if relay.Enqueued != 2 || len(relay.IDs) != 2 {
		t.Fatalf("unexpected relay response: %+v", relay)
	}

	queue1 := fetchQueue(t, server.URL, bobToken, bobDevice1)
	queue2 := fetchQueue(t, server.URL, bobToken, bobDevice2)

	env1 := findEnvelopeByCiphertext(queue1.Envelopes, ct1)
	if env1 == nil {
		t.Fatalf("device1 queue missing ct1: %+v", queue1.Envelopes)
	}
	if findEnvelopeByCiphertext(queue1.Envelopes, ct2) != nil {
		t.Fatalf("device1 queue must not contain device2 ciphertext")
	}

	env2 := findEnvelopeByCiphertext(queue2.Envelopes, ct2)
	if env2 == nil {
		t.Fatalf("device2 queue missing ct2: %+v", queue2.Envelopes)
	}
	if findEnvelopeByCiphertext(queue2.Envelopes, ct1) != nil {
		t.Fatalf("device2 queue must not contain device1 ciphertext")
	}

	// ACK on device1 must not remove device2's envelope.
	ackResp := postJSONWithHeaders(t, server.URL+"/api/messages/queue/ack", model.AckMessageQueueRequest{
		EnvelopeIDs: []string{env1.EnvelopeID},
	}, bobToken, bobDevice1)
	if ackResp.StatusCode != http.StatusOK {
		t.Fatalf("ack status = %d, want 200", ackResp.StatusCode)
	}

	queue1After := fetchQueue(t, server.URL, bobToken, bobDevice1)
	for _, item := range queue1After.Envelopes {
		if item.EnvelopeID == env1.EnvelopeID {
			t.Fatalf("device1 envelope still present after ack")
		}
	}

	queue2After := fetchQueue(t, server.URL, bobToken, bobDevice2)
	if findEnvelopeByCiphertext(queue2After.Envelopes, ct2) == nil {
		t.Fatalf("device2 envelope must survive device1 ack")
	}
}

func registerConfirmedDevice(t *testing.T, baseURL, token, deviceID, confirmingDeviceID string) {
	t.Helper()
	payload := sampleDevicePayload(deviceID)
	payload.Attestation = &model.DeviceAttestation{
		ConfirmingDeviceID: confirmingDeviceID,
		Signature:          base64.StdEncoding.EncodeToString([]byte("test-attestation")),
	}
	resp := postJSON(t, baseURL+"/api/devices", payload, token)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("register confirmed device status = %d", resp.StatusCode)
	}
	resp.Body.Close()
}

func fetchQueue(t *testing.T, baseURL, token, deviceID string) model.MessageQueueResponse {
	t.Helper()
	resp := getAuthWithHeaders(t, baseURL+"/api/messages/queue?limit=20", token, deviceID)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("queue status = %d, want 200 (device=%s)", resp.StatusCode, deviceID)
	}
	var queue model.MessageQueueResponse
	decodeJSON(t, resp, &queue)
	return queue
}

func findEnvelopeByCiphertext(envelopes []model.QueuedEnvelope, ciphertext string) *model.QueuedEnvelope {
	for i := range envelopes {
		if envelopes[i].Ciphertext == ciphertext {
			return &envelopes[i]
		}
	}
	return nil
}
