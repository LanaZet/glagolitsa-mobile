// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"fmt"
	"net/http"
	"testing"

	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func TestMessageRelayFlow_peerListHidesPendingOnlyRecipientDevices(t *testing.T) {
	// For other accounts, ListUserDevices uses activeOnly=true, so pending-only
	// recipients look like "no devices" to senders (cannot fan-out).
	server, mem := newFlowTestServer(t)
	suffix := fmt.Sprintf("%d", store.NowUTC().UnixNano())
	aliceToken, _ := registerUser(t, server.URL, alphaTestUsername("listalice"))
	bobToken, bobID := registerUser(t, server.URL, alphaTestUsername("listbob"))
	registerDevice(t, server.URL, aliceToken, "alice-dev-"+suffix)
	bobDevice := "bob-pending-" + suffix
	registerDevice(t, server.URL, bobToken, bobDevice)

	if err := mem.SetDeviceStatus(bobDevice, bobID, model.DeviceStatusPending); err != nil {
		t.Fatalf("set pending: %v", err)
	}

	// Viewer (alice) → empty list (activeOnly)
	resp := getAuth(t, server.URL+"/api/users/"+bobID+"/devices", aliceToken)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("peer list status = %d", resp.StatusCode)
	}
	var peerView []model.UserDevice
	decodeJSON(t, resp, &peerView)
	if len(peerView) != 0 {
		t.Fatalf("peer list of pending-only account = %d devices, want 0", len(peerView))
	}
	resp.Body.Close()

	// Owner (bob) → still sees pending device
	own := getAuth(t, server.URL+"/api/users/"+bobID+"/devices", bobToken)
	if own.StatusCode != http.StatusOK {
		t.Fatalf("own list status = %d", own.StatusCode)
	}
	var ownView []model.UserDevice
	decodeJSON(t, own, &ownView)
	if len(ownView) == 0 {
		t.Fatal("owner should see own pending device")
	}
	foundPending := false
	for _, d := range ownView {
		if d.DeviceID == bobDevice && d.DeviceStatus == model.DeviceStatusPending {
			foundPending = true
		}
	}
	if !foundPending {
		t.Fatalf("owner list missing pending device: %+v", ownView)
	}
	own.Body.Close()
}

func TestChatFlow_groupMessageForbiddenForNonMember(t *testing.T) {
	server, _ := newFlowTestServer(t)
	aliceToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("gma"))
	_, bobID, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("gmb"))
	carolToken, _, carolDevice := registerUserWithDevice(t, server.URL, alphaTestUsername("gmc"))

	resp := postJSON(t, server.URL+"/api/chats", model.CreateChatRequest{
		Title:     "secret-group",
		MemberIDs: []string{bobID},
	}, aliceToken)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("create group status = %d", resp.StatusCode)
	}
	var group model.Chat
	decodeJSON(t, resp, &group)
	resp.Body.Close()

	envelopeType := 4
	send := postJSONWithHeaders(t, server.URL+"/api/chats/"+group.ID+"/messages", model.SendMessageRequest{
		Ciphertext:     fakeCiphertextB64(),
		EnvelopeType:   &envelopeType,
		SenderDeviceID: carolDevice,
		PendingID:      "pending-non-member",
	}, carolToken, carolDevice)
	if send.StatusCode != http.StatusForbidden && send.StatusCode != http.StatusNotFound {
		t.Fatalf("non-member group send status = %d, want 403 or 404", send.StatusCode)
	}
	send.Body.Close()
}
