// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package model

import "time"

const (
	ChatEventMessageSent     = "message.sent"
	ChatEventMessageDeleted  = "message.deleted"
	ChatEventEnvelopeRelayed = "envelope.relayed"
	ChatEventMemberJoined    = "member.joined"
	ChatEventMemberRemoved   = "member.removed"
	ChatEventMemberLeft      = "member.left"
	ChatEventChatCreated     = "chat.created"

	DeliveryQueued    = "queued"
	DeliveryFetched   = "fetched"
	DeliveryAcked     = "acked"

)

type ChatEvent struct {
	ID        string         `json:"id"`
	ChatID    string         `json:"chat_id,omitempty"`
	EventType string         `json:"event_type"`
	ActorID   string         `json:"actor_id,omitempty"`
	EntityID  string         `json:"entity_id,omitempty"`
	Metadata  map[string]any `json:"metadata,omitempty"`
	CreatedAt time.Time      `json:"created_at"`
}

type SyncResponse struct {
	ServerTime time.Time   `json:"server_time"`
	Chats      []Chat      `json:"chats"`
	Events     []ChatEvent `json:"events"`
	HasMore    bool        `json:"has_more"`
}

type DeleteMessageResponse struct {
	MessageID string    `json:"message_id"`
	DeletedAt time.Time `json:"deleted_at"`
}

type MarkReadRequest struct {
	ChatID     string   `json:"chat_id"`
	MessageIDs []string `json:"message_ids"`
}
