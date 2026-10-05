// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package store

import (
	"log"
	"os"
	"strings"

	"github.com/google/uuid"

	"glagolitsa/server/internal/auth"
	"glagolitsa/server/internal/model"
)

const (
	DevUsername = "Marco"
	DevPassword = "marco123"
	DevPoloUser = "Polo"
	DevPoloPass = "polo123"
)

// DevReservedUsernames — служебные аккаунты из seed; реальные пользователи не могут их занять.
var DevReservedUsernames = map[string]struct{}{
	strings.ToLower(DevUsername): {},
	strings.ToLower(DevPoloUser): {},
}

func IsDevReservedUsername(username string) bool {
	_, ok := DevReservedUsernames[strings.ToLower(strings.TrimSpace(username))]
	return ok
}

func seedDevUsersEnabled() bool {
	v := strings.ToLower(strings.TrimSpace(os.Getenv("SEED_DEV_USERS")))
	return v == "true" || v == "1" || v == "yes"
}

func SeedDevData(s Store) {
	if !seedDevUsersEnabled() {
		return
	}
	seedUser(s, DevUsername, DevPassword)
	seedUser(s, DevPoloUser, DevPoloPass)

	devUser, _, err := s.GetUserByUsername(DevUsername)
	if err != nil {
		log.Printf("seed: dev user %s missing: %v", DevUsername, err)
		return
	}

	chats, err := s.ListChatsForUser(devUser.ID)
	if err != nil {
		log.Printf("seed: list chats failed: %v", err)
		return
	}
	if len(chats) > 0 {
		log.Printf("seed: dev data already present")
		return
	}

	chat := model.Chat{
		ID:        uuid.NewString(),
		Title:     "Demo chat",
		Type:      model.ChatTypeGroup,
		MemberIDs: []string{devUser.ID},
		CreatedAt: NowUTC(),
	}
	if _, err := s.CreateChat(chat); err != nil {
		log.Printf("seed: create chat failed: %v", err)
		return
	}

	message := model.Message{
		ID:        uuid.NewString(),
		ChatID:    chat.ID,
		SenderID:  devUser.ID,
		Body:      "Welcome to Glagolitsa! This is a demo message.",
		CreatedAt: NowUTC(),
	}
	if _, err := s.AddMessage(message); err != nil {
		log.Printf("seed: create message failed: %v", err)
		return
	}

	log.Printf("seed: dev accounts enabled — %s and %s", DevUsername, DevPoloUser)
}

func seedUser(s Store, username, password string) {
	if _, _, err := s.GetUserByUsername(username); err == nil {
		return
	}

	passwordHash, err := auth.HashPassword(password)
	if err != nil {
		log.Printf("seed: hash password for %s: %v", username, err)
		return
	}

	user, err := s.CreateUser(model.User{
		ID:        uuid.NewString(),
		Username:  username,
		CreatedAt: NowUTC(),
	}, passwordHash)
	if err != nil && err != ErrAlreadyExists {
		log.Printf("seed: create user %s: %v", username, err)
		return
	}
	if err == nil {
		_ = s.ActivateAccount(user.ID)
	}
}
