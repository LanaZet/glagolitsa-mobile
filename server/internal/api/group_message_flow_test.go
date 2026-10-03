// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"encoding/base64"
	"fmt"
	"net/http"
	"testing"

	groupapi "glagolitsa/server/internal/group"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func setupGroupChat(t *testing.T, baseURL string) (aliceToken, bobToken, groupID, aliceDevice string) {
	t.Helper()
	aliceToken, _, aliceDevice = registerUserWithDevice(t, baseURL, alphaTestUsername("grpmsgalice"))
	bobToken, bobID, _ := registerUserWithDevice(t, baseURL, alphaTestUsername("grpmsgbob"))

	resp := postJSON(t, baseURL+"/api/chats", model.CreateChatRequest{
		Title:     "Secure Group",
		MemberIDs: []string{bobID},
	}, aliceToken)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("create group status = %d", resp.StatusCode)
	}
	var group model.Chat
	decodeJSON(t, resp, &group)
	return aliceToken, bobToken, group.ID, aliceDevice
}

func TestGroupMessageFlow_plaintextBodyRejected(t *testing.T) {
	server, _ := newFlowTestServer(t)
	aliceToken, _, groupID, _ := setupGroupChat(t, server.URL)

	resp := postJSON(t, server.URL+"/api/chats/"+groupID+"/messages", model.SendMessageRequest{
		Body: "plaintext group message",
	}, aliceToken)
	if resp.StatusCode != http.StatusGone {
		t.Fatalf("status = %d, want 410", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestGroupMessageFlow_encryptedSenderKeyEnvelope(t *testing.T) {
	server, _ := newFlowTestServer(t)
	aliceToken, bobToken, groupID, aliceDevice := setupGroupChat(t, server.URL)

	envelopeType := 4
	ciphertext := base64.StdEncoding.EncodeToString([]byte("group-ciphertext-bytes"))
	sendResp := postJSON(t, server.URL+"/api/chats/"+groupID+"/messages", model.SendMessageRequest{
		Ciphertext:     ciphertext,
		EnvelopeType:   &envelopeType,
		SenderDeviceID: aliceDevice,
		PendingID:      "pending-group-1",
	}, aliceToken)
	if sendResp.StatusCode != http.StatusCreated {
		t.Fatalf("send status = %d", sendResp.StatusCode)
	}
	var sent model.Message
	decodeJSON(t, sendResp, &sent)
	if sent.Ciphertext != ciphertext {
		t.Fatalf("ciphertext not stored")
	}
	if sent.Body != "" {
		t.Fatalf("server must not store plaintext body, got %q", sent.Body)
	}

	listResp := getAuth(t, server.URL+"/api/chats/"+groupID+"/messages?limit=20", bobToken)
	if listResp.StatusCode != http.StatusOK {
		t.Fatalf("list status = %d", listResp.StatusCode)
	}
	var page model.MessagesPageResponse
	decodeJSON(t, listResp, &page)
	if len(page.Messages) != 1 {
		t.Fatalf("messages = %d, want 1", len(page.Messages))
	}
	if page.Messages[0].Body != "" {
		t.Fatalf("listed message must not expose plaintext body")
	}
}

func TestGroupMessageFlow_memberCanDeletePeerMessage(t *testing.T) {
	server, _ := newFlowTestServer(t)
	aliceToken, bobToken, groupID, aliceDevice := setupGroupChat(t, server.URL)

	envelopeType := 4
	sendResp := postJSON(t, server.URL+"/api/chats/"+groupID+"/messages", model.SendMessageRequest{
		Ciphertext:     fakeCiphertextB64(),
		EnvelopeType:   &envelopeType,
		SenderDeviceID: aliceDevice,
		PendingID:      "pending-delete-peer",
	}, aliceToken)
	if sendResp.StatusCode != http.StatusCreated {
		t.Fatalf("send status = %d", sendResp.StatusCode)
	}
	var sent model.Message
	decodeJSON(t, sendResp, &sent)

	delResp := deleteAuth(t, server.URL+"/api/chats/"+groupID+"/messages/"+sent.ID, bobToken)
	if delResp.StatusCode != http.StatusOK {
		t.Fatalf("delete status = %d, want 200", delResp.StatusCode)
	}
	delResp.Body.Close()

	for _, token := range []string{aliceToken, bobToken} {
		listResp := getAuth(t, server.URL+"/api/chats/"+groupID+"/messages?limit=20", token)
		if listResp.StatusCode != http.StatusOK {
			t.Fatalf("list status = %d", listResp.StatusCode)
		}
		var page model.MessagesPageResponse
		decodeJSON(t, listResp, &page)
		if len(page.Messages) != 0 {
			t.Fatalf("messages = %d, want 0", len(page.Messages))
		}
	}
}

func TestGroupMessageFlow_nonMemberCannotSend(t *testing.T) {
	server, _ := newFlowTestServer(t)
	_, _, groupID, aliceDevice := setupGroupChat(t, server.URL)
	charlieToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("grpmsgcharlie"))

	envelopeType := 4
	resp := postJSON(t, server.URL+"/api/chats/"+groupID+"/messages", model.SendMessageRequest{
		Ciphertext:     fakeCiphertextB64(),
		EnvelopeType:   &envelopeType,
		SenderDeviceID: aliceDevice,
		PendingID:      "pending-non-member",
	}, charlieToken)
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestGroupMessageFlow_channelSubscriberCannotPublishWhenAdminOnly(t *testing.T) {
	server, _ := newFlowTestServer(t)
	suffix := store.NowUTC().UnixNano()
	aliceToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("chanadmin"))
	bobToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("chansub"))
	slug := fmt.Sprintf("chanacl%d", suffix%1_000_000)

	createResp := postJSON(t, server.URL+"/api/channels", groupapi.CreateChannelRequest{
		Title:      "Announcements",
		Visibility: model.VisibilityPublic,
		Slug:       slug,
	}, aliceToken)
	if createResp.StatusCode != http.StatusCreated {
		t.Fatalf("create channel status = %d", createResp.StatusCode)
	}
	var created struct {
		ID string `json:"id"`
	}
	decodeJSON(t, createResp, &created)

	joinResp := postJSON(t, server.URL+"/api/channels/slug/"+slug+"/join", map[string]string{}, bobToken)
	if joinResp.StatusCode != http.StatusOK {
		t.Fatalf("join channel status = %d", joinResp.StatusCode)
	}
	joinResp.Body.Close()

	// Open channel posts: subscriber must not publish to feed.
	resp := postJSON(t, server.URL+"/api/chats/"+created.ID+"/messages", model.SendMessageRequest{
		Body:      "subscriber should not post",
		PendingID: "pending-channel-subscriber",
	}, bobToken)
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestChannelOpenPost_adminCanPublishPlaintext(t *testing.T) {
	server, _ := newFlowTestServer(t)
	suffix := store.NowUTC().UnixNano()
	aliceToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("chanpub"))
	bobToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("chansub"))
	slug := fmt.Sprintf("chanopen%d", suffix%1_000_000)

	createResp := postJSON(t, server.URL+"/api/channels", groupapi.CreateChannelRequest{
		Title:      "Creative",
		Visibility: model.VisibilityPublic,
		Slug:       slug,
	}, aliceToken)
	if createResp.StatusCode != http.StatusCreated {
		t.Fatalf("create channel status = %d", createResp.StatusCode)
	}
	var created struct {
		ID string `json:"id"`
	}
	decodeJSON(t, createResp, &created)

	joinResp := postJSON(t, server.URL+"/api/channels/slug/"+slug+"/join", map[string]string{}, bobToken)
	if joinResp.StatusCode != http.StatusOK {
		t.Fatalf("join status = %d", joinResp.StatusCode)
	}
	joinResp.Body.Close()

	sendResp := postJSON(t, server.URL+"/api/chats/"+created.ID+"/messages", model.SendMessageRequest{
		Body:      "Первый пост в канале",
		PendingID: "pending-channel-open-1",
	}, aliceToken)
	if sendResp.StatusCode != http.StatusCreated {
		t.Fatalf("send status = %d", sendResp.StatusCode)
	}
	var sent model.Message
	decodeJSON(t, sendResp, &sent)
	if sent.Body != "Первый пост в канале" {
		t.Fatalf("body = %q", sent.Body)
	}
	if sent.Ciphertext != "" {
		t.Fatalf("channel post must not store ciphertext")
	}

	listResp := getAuth(t, server.URL+"/api/chats/"+created.ID+"/messages?limit=20", bobToken)
	if listResp.StatusCode != http.StatusOK {
		t.Fatalf("list status = %d", listResp.StatusCode)
	}
	var page model.MessagesPageResponse
	decodeJSON(t, listResp, &page)
	if len(page.Messages) != 1 || page.Messages[0].Body != "Первый пост в канале" {
		t.Fatalf("subscriber should see open body, got %+v", page.Messages)
	}

	// Ciphertext on channel is rejected.
	bad := postJSON(t, server.URL+"/api/chats/"+created.ID+"/messages", model.SendMessageRequest{
		Body:       "x",
		Ciphertext: fakeCiphertextB64(),
	}, aliceToken)
	if bad.StatusCode != http.StatusBadRequest {
		t.Fatalf("ciphertext channel post status = %d, want 400", bad.StatusCode)
	}
	bad.Body.Close()
}

func TestChannelOpenPost_commentDeniedWhenPermNone(t *testing.T) {
	server, _ := newFlowTestServer(t)
	suffix := store.NowUTC().UnixNano()
	aliceToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("channonea"))
	bobToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("channoneb"))
	slug := fmt.Sprintf("channone%d", suffix%1_000_000)

	createResp := postJSON(t, server.URL+"/api/channels", groupapi.CreateChannelRequest{
		Title: "Silent", Visibility: model.VisibilityPublic, Slug: slug,
	}, aliceToken)
	var created struct {
		ID string `json:"id"`
	}
	decodeJSON(t, createResp, &created)
	joinResp := postJSON(t, server.URL+"/api/channels/slug/"+slug+"/join", map[string]string{}, bobToken)
	joinResp.Body.Close()

	none := "none"
	patch := patchJSON(t, server.URL+"/api/groups/"+created.ID+"/settings", groupapi.UpdateSettingsRequest{
		PermComment: &none,
	}, aliceToken)
	if patch.StatusCode != http.StatusOK {
		t.Fatalf("settings status = %d", patch.StatusCode)
	}
	patch.Body.Close()

	postResp := postJSON(t, server.URL+"/api/chats/"+created.ID+"/messages", model.SendMessageRequest{
		Body: "post", PendingID: "root-none",
	}, aliceToken)
	var root model.Message
	decodeJSON(t, postResp, &root)

	cmt := postJSON(t, server.URL+"/api/chats/"+created.ID+"/messages", model.SendMessageRequest{
		Body: "nope", ThreadRootID: root.ID, PendingID: "cmt-none",
	}, bobToken)
	if cmt.StatusCode != http.StatusForbidden {
		t.Fatalf("comment status = %d, want 403", cmt.StatusCode)
	}
	cmt.Body.Close()
}

func TestChannelPostsFeed_excludesCommentsAndGalleryFiltersMedia(t *testing.T) {
	server, _ := newFlowTestServer(t)
	suffix := store.NowUTC().UnixNano()
	aliceToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("chanfeeda"))
	bobToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("chanfeedb"))
	slug := fmt.Sprintf("chanfeed%d", suffix%1_000_000)

	createResp := postJSON(t, server.URL+"/api/channels", groupapi.CreateChannelRequest{
		Title: "Feed", Visibility: model.VisibilityPublic, Slug: slug,
	}, aliceToken)
	var created struct {
		ID string `json:"id"`
	}
	decodeJSON(t, createResp, &created)
	joinResp := postJSON(t, server.URL+"/api/channels/slug/"+slug+"/join", map[string]string{}, bobToken)
	joinResp.Body.Close()

	textPost := postJSON(t, server.URL+"/api/chats/"+created.ID+"/messages", model.SendMessageRequest{
		Body: "text only", PendingID: "p-text",
	}, aliceToken)
	var textMsg model.Message
	decodeJSON(t, textPost, &textMsg)

	mediaPost := postJSON(t, server.URL+"/api/chats/"+created.ID+"/messages", model.SendMessageRequest{
		Body:      "with art",
		PendingID: "p-media",
		Metadata: map[string]any{
			"kind": "creative_post",
			"media": []map[string]any{
				{"file_id": "file-1", "cover": true},
			},
		},
	}, aliceToken)
	var mediaMsg model.Message
	decodeJSON(t, mediaPost, &mediaMsg)

	cmt := postJSON(t, server.URL+"/api/chats/"+created.ID+"/messages", model.SendMessageRequest{
		Body: "comment", ThreadRootID: mediaMsg.ID, PendingID: "p-cmt",
	}, bobToken)
	if cmt.StatusCode != http.StatusCreated {
		t.Fatalf("comment = %d", cmt.StatusCode)
	}
	cmt.Body.Close()

	feedResp := getAuth(t, server.URL+"/api/chats/"+created.ID+"/posts?limit=20", bobToken)
	if feedResp.StatusCode != http.StatusOK {
		t.Fatalf("feed status = %d", feedResp.StatusCode)
	}
	var feed model.MessagesPageResponse
	decodeJSON(t, feedResp, &feed)
	if len(feed.Messages) != 2 {
		t.Fatalf("feed roots = %d, want 2 (comments excluded)", len(feed.Messages))
	}

	galResp := getAuth(t, server.URL+"/api/chats/"+created.ID+"/gallery?limit=20", bobToken)
	if galResp.StatusCode != http.StatusOK {
		t.Fatalf("gallery status = %d", galResp.StatusCode)
	}
	var gallery model.MessagesPageResponse
	decodeJSON(t, galResp, &gallery)
	if len(gallery.Messages) != 1 || gallery.Messages[0].ID != mediaMsg.ID {
		t.Fatalf("gallery = %+v, want only media post", gallery.Messages)
	}
}

func TestChannelReactions_setAndClear(t *testing.T) {
	server, _ := newFlowTestServer(t)
	suffix := store.NowUTC().UnixNano()
	aliceToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("chanrxa"))
	bobToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("chanrxb"))
	slug := fmt.Sprintf("chanrx%d", suffix%1_000_000)

	createResp := postJSON(t, server.URL+"/api/channels", groupapi.CreateChannelRequest{
		Title: "React", Visibility: model.VisibilityPublic, Slug: slug,
	}, aliceToken)
	var created struct {
		ID string `json:"id"`
	}
	decodeJSON(t, createResp, &created)
	joinResp := postJSON(t, server.URL+"/api/channels/slug/"+slug+"/join", map[string]string{}, bobToken)
	joinResp.Body.Close()

	postResp := postJSON(t, server.URL+"/api/chats/"+created.ID+"/messages", model.SendMessageRequest{
		Body: "art", PendingID: "rx-root",
	}, aliceToken)
	var root model.Message
	decodeJSON(t, postResp, &root)

	put := putJSON(t, server.URL+"/api/messages/"+root.ID+"/reactions", model.SetReactionRequest{Emoji: "❤️"}, bobToken)
	if put.StatusCode != http.StatusOK {
		t.Fatalf("set reaction status = %d", put.StatusCode)
	}
	var setBody struct {
		Summary []model.ReactionSummary `json:"summary"`
	}
	decodeJSON(t, put, &setBody)
	if len(setBody.Summary) != 1 || setBody.Summary[0].Emoji != "❤️" || setBody.Summary[0].Count != 1 {
		t.Fatalf("summary = %+v", setBody.Summary)
	}

	del := deleteAuth(t, server.URL+"/api/messages/"+root.ID+"/reactions", bobToken)
	if del.StatusCode != http.StatusOK {
		t.Fatalf("clear reaction status = %d", del.StatusCode)
	}
	del.Body.Close()
}

func TestChannelOpenPost_memberCanComment(t *testing.T) {
	server, _ := newFlowTestServer(t)
	suffix := store.NowUTC().UnixNano()
	aliceToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("chancmta"))
	bobToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("chancmtb"))
	slug := fmt.Sprintf("chancmt%d", suffix%1_000_000)

	createResp := postJSON(t, server.URL+"/api/channels", groupapi.CreateChannelRequest{
		Title: "Discuss", Visibility: model.VisibilityPublic, Slug: slug,
	}, aliceToken)
	if createResp.StatusCode != http.StatusCreated {
		t.Fatalf("create = %d", createResp.StatusCode)
	}
	var created struct {
		ID string `json:"id"`
	}
	decodeJSON(t, createResp, &created)
	joinResp := postJSON(t, server.URL+"/api/channels/slug/"+slug+"/join", map[string]string{}, bobToken)
	if joinResp.StatusCode != http.StatusOK {
		t.Fatalf("join = %d", joinResp.StatusCode)
	}
	joinResp.Body.Close()

	postResp := postJSON(t, server.URL+"/api/chats/"+created.ID+"/messages", model.SendMessageRequest{
		Body: "work", PendingID: "root-1",
	}, aliceToken)
	if postResp.StatusCode != http.StatusCreated {
		t.Fatalf("post = %d", postResp.StatusCode)
	}
	var root model.Message
	decodeJSON(t, postResp, &root)

	cmt := postJSON(t, server.URL+"/api/chats/"+created.ID+"/messages", model.SendMessageRequest{
		Body:         "nice!",
		ThreadRootID: root.ID,
		PendingID:    "cmt-1",
	}, bobToken)
	if cmt.StatusCode != http.StatusCreated {
		t.Fatalf("comment status = %d", cmt.StatusCode)
	}
	var comment model.Message
	decodeJSON(t, cmt, &comment)
	if comment.ThreadRootID != root.ID || comment.Body != "nice!" {
		t.Fatalf("comment = %+v", comment)
	}
}

func TestGroupMessageFlow_validationErrors(t *testing.T) {
	server, _ := newFlowTestServer(t)
	aliceToken, _, groupID, aliceDevice := setupGroupChat(t, server.URL)

	tests := []struct {
		name string
		body model.SendMessageRequest
	}{
		{
			name: "missing envelope type",
			body: model.SendMessageRequest{
				Ciphertext:     fakeCiphertextB64(),
				SenderDeviceID: aliceDevice,
			},
		},
		{
			name: "wrong envelope type",
			body: func() model.SendMessageRequest {
				t := 3
				return model.SendMessageRequest{
					Ciphertext:     fakeCiphertextB64(),
					EnvelopeType:   &t,
					SenderDeviceID: aliceDevice,
				}
			}(),
		},
		{
			name: "missing sender device",
			body: func() model.SendMessageRequest {
				t := 4
				return model.SendMessageRequest{
					Ciphertext:   fakeCiphertextB64(),
					EnvelopeType: &t,
				}
			}(),
		},
		{
			name: "invalid ciphertext base64",
			body: func() model.SendMessageRequest {
				t := 4
				return model.SendMessageRequest{
					Ciphertext:     "%%%",
					EnvelopeType:   &t,
					SenderDeviceID: aliceDevice,
				}
			}(),
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			resp := postJSON(t, server.URL+"/api/chats/"+groupID+"/messages", tc.body, aliceToken)
			if resp.StatusCode != http.StatusBadRequest {
				t.Fatalf("status = %d, want 400", resp.StatusCode)
			}
			resp.Body.Close()
		})
	}
}

func TestGroupMessageFlow_dmPlaintextStillRejected(t *testing.T) {
	server, _ := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	resp := postJSON(t, server.URL+"/api/chats/"+peers.chatID+"/messages", model.SendMessageRequest{
		Body: "must not work",
	}, peers.aliceToken)
	if resp.StatusCode != http.StatusGone {
		t.Fatalf("dm plaintext status = %d, want 410", resp.StatusCode)
	}
	resp.Body.Close()
}
