// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"fmt"
	"net/http"
	"testing"

	"glagolitsa/server/internal/model"
)

func registerUserWithDevice(t *testing.T, baseURL, username string) (token, userID, deviceID string) {
	t.Helper()
	token, userID = registerUser(t, baseURL, username)
	deviceID = fmt.Sprintf("dev-%s", userID[:8])
	registerDevice(t, baseURL, token, deviceID)
	return token, userID, deviceID
}

// Mattermost-style: chat list/create/get before message delivery.
func TestChatFlow_listChatsRequiresAuth(t *testing.T) {
	server, _ := newFlowTestServer(t)
	resp := getAuth(t, server.URL+"/api/chats", "")
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestChatFlow_createDmAndListForBothMembers(t *testing.T) {
	server, _ := newFlowTestServer(t)
	aliceToken, aliceID, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("chatalice"))
	bobToken, bobID, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("chatbob"))

	dmResp := postJSON(t, server.URL+"/api/chats/dm", model.CreateDMRequest{UserID: bobID}, aliceToken)
	if dmResp.StatusCode != http.StatusOK {
		t.Fatalf("create dm status = %d", dmResp.StatusCode)
	}
	var dm model.Chat
	decodeJSON(t, dmResp, &dm)
	if dm.Type != model.ChatTypeDM {
		t.Fatalf("type = %q, want dm", dm.Type)
	}

	aliceChats := getAuth(t, server.URL+"/api/chats", aliceToken)
	if aliceChats.StatusCode != http.StatusOK {
		t.Fatalf("alice chats status = %d", aliceChats.StatusCode)
	}
	var aliceList []model.Chat
	decodeJSON(t, aliceChats, &aliceList)
	if len(aliceList) != 1 || aliceList[0].ID != dm.ID {
		t.Fatalf("alice chat list = %+v", aliceList)
	}

	bobChats := getAuth(t, server.URL+"/api/chats", bobToken)
	if bobChats.StatusCode != http.StatusOK {
		t.Fatalf("bob chats status = %d", bobChats.StatusCode)
	}
	var bobList []model.Chat
	decodeJSON(t, bobChats, &bobList)
	if len(bobList) != 1 {
		t.Fatalf("bob chat list = %+v", bobList)
	}

	getResp := getAuth(t, server.URL+"/api/chats/"+dm.ID, bobToken)
	if getResp.StatusCode != http.StatusOK {
		t.Fatalf("get chat status = %d", getResp.StatusCode)
	}
	var fetched model.Chat
	decodeJSON(t, getResp, &fetched)
	if fetched.ID != dm.ID {
		t.Fatalf("fetched id = %q", fetched.ID)
	}
	_ = aliceID
}

func TestChatFlow_createDmIsIdempotent(t *testing.T) {
	server, _ := newFlowTestServer(t)
	aliceToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("dmidema"))
	_, bobID, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("dmidemb"))

	first := postJSON(t, server.URL+"/api/chats/dm", model.CreateDMRequest{UserID: bobID}, aliceToken)
	var chat1 model.Chat
	decodeJSON(t, first, &chat1)

	second := postJSON(t, server.URL+"/api/chats/dm", model.CreateDMRequest{UserID: bobID}, aliceToken)
	var chat2 model.Chat
	decodeJSON(t, second, &chat2)
	if chat1.ID != chat2.ID {
		t.Fatalf("dm ids differ: %q vs %q", chat1.ID, chat2.ID)
	}
}

func TestChatFlow_createDmRejectsSelf(t *testing.T) {
	server, _ := newFlowTestServer(t)
	token, userID, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("dmself"))

	resp := postJSON(t, server.URL+"/api/chats/dm", model.CreateDMRequest{UserID: userID}, token)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("self dm status = %d, want 400", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestChatFlow_createGroupChat(t *testing.T) {
	server, _ := newFlowTestServer(t)
	aliceToken, aliceID, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("grpalice"))
	_, bobID, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("grpbob"))

	resp := postJSON(t, server.URL+"/api/chats", model.CreateChatRequest{
		Title:     "Test Group",
		MemberIDs: []string{bobID},
	}, aliceToken)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("create group status = %d", resp.StatusCode)
	}
	var group model.Chat
	decodeJSON(t, resp, &group)
	if group.Type != model.ChatTypeGroup {
		t.Fatalf("type = %q, want group", group.Type)
	}
	if len(group.MemberIDs) < 2 {
		t.Fatalf("members = %v", group.MemberIDs)
	}
	_ = aliceID
}

func TestChatFlow_createGroupChatWithAvatar(t *testing.T) {
	server, _ := newFlowTestServer(t)
	aliceToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("grpavalice"))
	icon := "data:image/png;base64,Z3JvdXBpY29u"

	resp := postJSON(t, server.URL+"/api/chats", model.CreateChatRequest{
		Title:     "Icon Group",
		AvatarURL: icon,
	}, aliceToken)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("create group status = %d", resp.StatusCode)
	}
	var group model.Chat
	decodeJSON(t, resp, &group)
	if group.AvatarURL != icon {
		t.Fatalf("avatar = %q", group.AvatarURL)
	}

	updatedURL := "data:image/jpeg;base64,dXBkYXRlZA=="
	patch := patchJSON(t, server.URL+"/api/chats/"+group.ID, model.UpdateChatRequest{
		AvatarURL: &updatedURL,
	}, aliceToken)
	if patch.StatusCode != http.StatusOK {
		t.Fatalf("patch avatar status = %d", patch.StatusCode)
	}
	var updated model.Chat
	decodeJSON(t, patch, &updated)
	if updated.AvatarURL != updatedURL {
		t.Fatalf("updated avatar = %q", updated.AvatarURL)
	}
}

func TestChatFlow_updateGroupAvatarRejectsNonMember(t *testing.T) {
	server, _ := newFlowTestServer(t)
	aliceToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("grpavowner"))
	carolToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("grpavcarol"))

	resp := postJSON(t, server.URL+"/api/chats", model.CreateChatRequest{
		Title: "Icon Group",
	}, aliceToken)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("create group status = %d", resp.StatusCode)
	}
	var group model.Chat
	decodeJSON(t, resp, &group)

	updatedURL := "data:image/png;base64,dXBkYXRlZA=="
	patch := patchJSON(t, server.URL+"/api/chats/"+group.ID, model.UpdateChatRequest{
		AvatarURL: &updatedURL,
	}, carolToken)
	if patch.StatusCode != http.StatusForbidden {
		t.Fatalf("patch avatar status = %d, want 403", patch.StatusCode)
	}
	patch.Body.Close()
}

func TestChatFlow_getChatForbiddenForNonMember(t *testing.T) {
	server, _ := newFlowTestServer(t)
	aliceToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("forbida"))
	_, bobID, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("forbidb"))
	carolToken, _, _ := registerUserWithDevice(t, server.URL, alphaTestUsername("forbidc"))

	dmResp := postJSON(t, server.URL+"/api/chats/dm", model.CreateDMRequest{UserID: bobID}, aliceToken)
	var dm model.Chat
	decodeJSON(t, dmResp, &dm)

	resp := getAuth(t, server.URL+"/api/chats/"+dm.ID, carolToken)
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("non-member get chat status = %d, want 403", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestChatFlow_listMessagesEmptyForNewChat(t *testing.T) {
	server, _ := newFlowTestServer(t)
	peers := setupRelayPeers(t, server.URL)

	resp := getAuth(t, server.URL+"/api/chats/"+peers.chatID+"/messages?limit=20", peers.aliceToken)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("list messages status = %d", resp.StatusCode)
	}
	var page model.MessagesPageResponse
	decodeJSON(t, resp, &page)
	if len(page.Messages) != 0 {
		t.Fatalf("expected empty messages, got %d", len(page.Messages))
	}
}
