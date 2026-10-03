// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package model

import "time"

type User struct {
	ID          string    `json:"id"`
	Username    string    `json:"username"`
	Email       string    `json:"email,omitempty"`
	DisplayName string    `json:"display_name,omitempty"`
	Status      string    `json:"status,omitempty"`
	Bio         string    `json:"bio,omitempty"`
	AvatarURL   string    `json:"avatar_url,omitempty"`
	Presence    string    `json:"presence,omitempty"`
	Nickname    string    `json:"nickname,omitempty"`
	Position    string    `json:"position,omitempty"`
	CreatedAt   time.Time `json:"created_at"`
}

type UpdateProfileRequest struct {
	DisplayName *string `json:"display_name"`
	Status      *string `json:"status"`
	Bio         *string `json:"bio"`
	AvatarURL   *string `json:"avatar_url"`
	Presence    *string `json:"presence"`
	Nickname    *string `json:"nickname"`
	Position    *string `json:"position"`
}

const (
	ChatTypeGroup   = "group"
	ChatTypeDM      = "dm"
	ChatTypeChannel = "channel"

	// Conversation policies (Matrix-like flags; product surface still group vs channel).
	VisibilityPrivate = "private"
	VisibilityPublic  = "public"
	EncryptionE2E     = "e2e"
	EncryptionNone    = "none"
)

type Chat struct {
	ID            string    `json:"id"`
	Title         string    `json:"title"`
	Type          string    `json:"type"`
	DMKey         string    `json:"-"`
	MemberIDs     []string  `json:"member_ids"`
	LastMessage   string    `json:"last_message,omitempty"`
	LastMessageAt time.Time `json:"last_message_at,omitempty"`
	CreatedAt     time.Time `json:"created_at"`
	// Policy projection (optional; filled for channel/group when available).
	Description string `json:"description,omitempty"`
	Visibility  string `json:"visibility,omitempty"`
	Slug        string `json:"slug,omitempty"`
	Encryption  string `json:"encryption,omitempty"`
	// AvatarURL is a data:image URI for group/channel icons.
	AvatarURL string `json:"avatar_url,omitempty"`
	// CreatorID is the owner (Mattermost CreatorId analogue) for delete/admin UI.
	CreatorID string `json:"creator_id,omitempty"`
}

type Message struct {
	ID                      string         `json:"id"`
	ChatID                  string         `json:"chat_id"`
	SenderID                string         `json:"sender_id"`
	Body                    string         `json:"body,omitempty"`
	PendingID               string         `json:"pending_id,omitempty"`
	CreatedAt               time.Time      `json:"created_at"`
	ReplyToMessageID        string         `json:"reply_to_message_id,omitempty"`
	ThreadRootID            string         `json:"thread_root_id,omitempty"`
	ThreadParentID          string         `json:"thread_parent_id,omitempty"`
	Visibility              string         `json:"visibility,omitempty"`
	ThreadReplyCount        int            `json:"thread_reply_count,omitempty"`
	LastThreadReplyAt       *time.Time     `json:"last_thread_reply_at,omitempty"`
	LastThreadReplySenderID string         `json:"last_thread_reply_sender_id,omitempty"`
	EnvelopeType            *int           `json:"envelope_type,omitempty"`
	Ciphertext              string         `json:"ciphertext,omitempty"`
	SenderDeviceID          string         `json:"sender_device_id,omitempty"`
	// MetadataJSON — open channel post extras (media[], tags, kind). Opaque to e2e groups.
	Metadata         map[string]any    `json:"metadata,omitempty"`
	ReactionSummary  []ReactionSummary `json:"reactions,omitempty"`
}

type CreateChatRequest struct {
	Title     string   `json:"title"`
	MemberIDs []string `json:"member_ids,omitempty"`
	AvatarURL string   `json:"avatar_url,omitempty"`
}

type UpdateChatRequest struct {
	AvatarURL *string `json:"avatar_url"`
}

type CreateDMRequest struct {
	UserID string `json:"user_id"`
}

type SendMessageRequest struct {
	Body             string         `json:"body,omitempty"`
	PendingID        string         `json:"pending_id,omitempty"`
	ReplyToMessageID string         `json:"reply_to_message_id,omitempty"`
	ThreadRootID     string         `json:"thread_root_id,omitempty"`
	ThreadParentID   string         `json:"thread_parent_id,omitempty"`
	Visibility       string         `json:"visibility,omitempty"`
	EnvelopeType     *int           `json:"envelope_type,omitempty"`
	Ciphertext       string         `json:"ciphertext,omitempty"`
	SenderDeviceID   string         `json:"sender_device_id,omitempty"`
	Metadata         map[string]any `json:"metadata,omitempty"`
}

// ReactionSummary is returned with posts/messages for open channels.
type ReactionSummary struct {
	Emoji   string   `json:"emoji"`
	Count   int      `json:"count"`
	Me      bool     `json:"me,omitempty"`
	UserIDs []string `json:"user_ids,omitempty"`
}

type SetReactionRequest struct {
	Emoji string `json:"emoji"`
}

type ReactionUpdatedData struct {
	MessageID string            `json:"message_id"`
	ChatID    string            `json:"chat_id"`
	Summary   []ReactionSummary `json:"summary"`
}

type WSEvent struct {
	Event string `json:"event"`
	Data  any    `json:"data"`
}

type MessageNewData struct {
	Message Message `json:"message"`
}

type ChatUpdatedData struct {
	Chat Chat `json:"chat"`
}

type MessagesPageResponse struct {
	Messages []Message `json:"messages"`
	HasMore  bool      `json:"has_more"`
}
