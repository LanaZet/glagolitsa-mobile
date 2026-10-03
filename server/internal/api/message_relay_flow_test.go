// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func postJSONWithHeaders(t *testing.T, url string, body any, token, deviceID string) *http.Response {
	t.Helper()
	payload, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if deviceID != "" {
		req.Header.Set("X-Device-Id", deviceID)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	return resp
}

func getAuthWithHeaders(t *testing.T, url, token, deviceID string) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if deviceID != "" {
		req.Header.Set("X-Device-Id", deviceID)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	return resp
}

func fakeCiphertextB64() string {
	return base64.StdEncoding.EncodeToString([]byte("fake-ciphertext-bytes"))
}

type relayPeers struct {
	aliceToken       string
	bobToken         string
	bobUserID        string
	aliceDevice      string
	bobDevice        string
	bobMailbox       string
	bobDeliveryToken string
	chatID           string
}

func setupRelayPeers(t *testing.T, baseURL string) relayPeers {
	t.Helper()
	suffix := fmt.Sprintf("%d", store.NowUTC().UnixNano())
	aliceToken, _ := registerUser(t, baseURL, alphaTestUsername("relayalice"))
	bobToken, bobUserID := registerUser(t, baseURL, alphaTestUsername("relaybob"))

	aliceDevice := "alice-device-" + suffix
	bobDevice := "bob-device-" + suffix
	registerDevice(t, baseURL, aliceToken, aliceDevice)
	registerDevice(t, baseURL, bobToken, bobDevice)

	bobDeviceInfo := lookupUserDevice(t, baseURL, aliceToken, bobUserID, bobDevice)

	resp := postJSON(t, baseURL+"/api/chats/dm", model.CreateDMRequest{UserID: bobUserID}, aliceToken)
	if resp.StatusCode != http.StatusCreated && resp.StatusCode != http.StatusOK {
		t.Fatalf("create dm status = %d", resp.StatusCode)
	}
	var chat model.Chat
	decodeJSON(t, resp, &chat)
	if chat.ID == "" {
		t.Fatal("dm chat id is empty")
	}

	return relayPeers{
		aliceToken:       aliceToken,
		bobToken:         bobToken,
		bobUserID:        bobUserID,
		aliceDevice:      aliceDevice,
		bobDevice:        bobDevice,
		bobMailbox:       bobDeviceInfo.MailboxToken,
		bobDeliveryToken: bobDeviceInfo.DeliveryToken,
		chatID:           chat.ID,
	}
}

func lookupMailboxToken(t *testing.T, baseURL, viewerToken, userID, deviceID string) string {
	t.Helper()
	return lookupUserDevice(t, baseURL, viewerToken, userID, deviceID).MailboxToken
}

func lookupUserDevice(t *testing.T, baseURL, viewerToken, userID, deviceID string) model.UserDevice {
	t.Helper()
	resp := getAuth(t, baseURL+"/api/users/"+userID+"/devices", viewerToken)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("list devices status = %d", resp.StatusCode)
	}
	var devices []model.UserDevice
	decodeJSON(t, resp, &devices)
	for _, device := range devices {
		if device.DeviceID == deviceID {
			if device.MailboxToken == "" {
				t.Fatalf("device %q has empty mailbox_token", deviceID)
			}
			if device.DeliveryToken == "" {
				t.Fatalf("device %q has empty delivery_token", deviceID)
			}
			return device
		}
	}
	t.Fatalf("device %q not found in user devices", deviceID)
	return model.UserDevice{}
}

func TestMessageRelayFlow_unauthorized(t *testing.T) {
	server, _ := newFlowTestServer(t)

	resp := postJSONWithHeaders(t, server.URL+"/api/messages/relay", model.RelayMessageRequest{
		Envelopes: []model.RelayEnvelopeRequest{{
			MailboxToken: "mailbox",
			EnvelopeType: 3,
			Ciphertext:   fakeCiphertextB64(),
		}},
	}, "", "")
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("relay without auth status = %d, want 401", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestMessageRelayFlow_withoutDevice(t *testing.T) {
	server, _ := newFlowTestServer(t)
	token, _ := registerUser(t, server.URL, alphaTestUsername("relaynodevice"))

	resp := postJSONWithHeaders(t, server.URL+"/api/messages/relay", model.RelayMessageRequest{
		Envelopes: []model.RelayEnvelopeRequest{{
			MailboxToken: "mailbox",
			EnvelopeType: 3,
			Ciphertext:   fakeCiphertextB64(),
		}},
	}, token, "")
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("relay without device status = %d, want 403", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestMessageRelayFlow_validationErrors(t *testing.T) {
	server, _ := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	tests := []struct {
		name string
		body model.RelayMessageRequest
	}{
		{
			name: "empty envelopes",
			body: model.RelayMessageRequest{Envelopes: nil},
		},
		{
			name: "invalid ciphertext base64",
			body: model.RelayMessageRequest{Envelopes: []model.RelayEnvelopeRequest{{
				MailboxToken:  peers.bobMailbox,
				DeliveryToken: peers.bobDeliveryToken,
				EnvelopeType:  3,
				Ciphertext:    "not-base64!!!",
			}}},
		},
		{
			name: "missing mailbox token",
			body: model.RelayMessageRequest{Envelopes: []model.RelayEnvelopeRequest{{
				EnvelopeType: 3,
				Ciphertext:   fakeCiphertextB64(),
			}}},
		},
		{
			name: "invalid envelope type",
			body: model.RelayMessageRequest{Envelopes: []model.RelayEnvelopeRequest{{
				MailboxToken:  peers.bobMailbox,
				DeliveryToken: peers.bobDeliveryToken,
				EnvelopeType:  2,
				Ciphertext:    fakeCiphertextB64(),
			}}},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			resp := postJSONWithHeaders(t, server.URL+"/api/messages/relay", tc.body, peers.aliceToken, peers.aliceDevice)
			if resp.StatusCode != http.StatusBadRequest {
				t.Fatalf("status = %d, want 400", resp.StatusCode)
			}
			resp.Body.Close()
		})
	}
}

func TestMessageRelayFlow_queueRequiresDeviceHeader(t *testing.T) {
	server, _ := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	resp := getAuth(t, server.URL+"/api/messages/queue?limit=10", peers.bobToken)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("queue without X-Device-Id status = %d, want 400", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestMessageRelayFlow_queueUnauthorized(t *testing.T) {
	server, _ := newFlowTestServer(t)

	resp := getAuthWithHeaders(t, server.URL+"/api/messages/queue?limit=10", "", "device-1")
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("queue without auth status = %d, want 401", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestMessageRelayFlow_ackRequiresDeviceHeader(t *testing.T) {
	server, _ := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	resp := postJSONWithHeaders(t, server.URL+"/api/messages/queue/ack", model.AckMessageQueueRequest{
		EnvelopeIDs: []string{"00000000-0000-0000-0000-000000000001"},
	}, peers.bobToken, "")
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("ack without X-Device-Id status = %d, want 400", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestMessageRelayFlow_plaintextDMDeprecated(t *testing.T) {
	server, _ := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	resp := postJSON(t, server.URL+"/api/chats/"+peers.chatID+"/messages", model.SendMessageRequest{
		Body: "plaintext",
	}, peers.aliceToken)
	if resp.StatusCode != http.StatusGone {
		t.Fatalf("plaintext DM status = %d, want 410", resp.StatusCode)
	}
	resp.Body.Close()
}

// Happy path: relay sealed envelope → poll queue → ACK (mirrors scripts/test-message-relay.sh).
func TestMessageRelayFlow_relayPollAck(t *testing.T) {
	server, _ := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)
	ciphertext := fakeCiphertextB64()

	relayResp := postJSONWithHeaders(t, server.URL+"/api/messages/relay", model.RelayMessageRequest{
		ClientMessageID: "pending-e2e-1",
		Envelopes: []model.RelayEnvelopeRequest{{
			MailboxToken:  peers.bobMailbox,
			DeliveryToken: peers.bobDeliveryToken,
			EnvelopeType:  3,
			Ciphertext:    ciphertext,
		}},
	}, peers.aliceToken, peers.aliceDevice)
	if relayResp.StatusCode != http.StatusCreated {
		t.Fatalf("relay status = %d, want 201", relayResp.StatusCode)
	}
	var relay model.RelayMessageResponse
	decodeJSON(t, relayResp, &relay)
	if relay.Enqueued != 1 || len(relay.IDs) != 1 {
		t.Fatalf("unexpected relay response: %+v", relay)
	}
	envelopeID := relay.IDs[0]

	queueResp := getAuthWithHeaders(t, server.URL+"/api/messages/queue?limit=10", peers.bobToken, peers.bobDevice)
	if queueResp.StatusCode != http.StatusOK {
		t.Fatalf("queue status = %d, want 200", queueResp.StatusCode)
	}
	var queue model.MessageQueueResponse
	decodeJSON(t, queueResp, &queue)

	var found *model.QueuedEnvelope
	for i := range queue.Envelopes {
		if queue.Envelopes[i].EnvelopeID == envelopeID {
			found = &queue.Envelopes[i]
			break
		}
	}
	if found == nil {
		t.Fatalf("envelope %q not in queue: %+v", envelopeID, queue.Envelopes)
	}
	if found.Ciphertext != ciphertext {
		t.Fatalf("ciphertext = %q, want %q", found.Ciphertext, ciphertext)
	}
	if found.SizeBucket < 16 {
		t.Fatalf("size_bucket = %d, want >= 16", found.SizeBucket)
	}

	ackResp := postJSONWithHeaders(t, server.URL+"/api/messages/queue/ack", model.AckMessageQueueRequest{
		EnvelopeIDs: []string{envelopeID},
	}, peers.bobToken, peers.bobDevice)
	if ackResp.StatusCode != http.StatusOK {
		t.Fatalf("ack status = %d, want 200", ackResp.StatusCode)
	}
	var ack model.AckMessageQueueResponse
	decodeJSON(t, ackResp, &ack)
	if ack.Deleted != 1 {
		t.Fatalf("deleted = %d, want 1", ack.Deleted)
	}

	queueAfterResp := getAuthWithHeaders(t, server.URL+"/api/messages/queue?limit=10", peers.bobToken, peers.bobDevice)
	if queueAfterResp.StatusCode != http.StatusOK {
		t.Fatalf("queue after ack status = %d, want 200", queueAfterResp.StatusCode)
	}
	var queueAfter model.MessageQueueResponse
	decodeJSON(t, queueAfterResp, &queueAfter)
	for _, item := range queueAfter.Envelopes {
		if item.EnvelopeID == envelopeID {
			t.Fatalf("envelope %q still present after ack", envelopeID)
		}
	}
}
