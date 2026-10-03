// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package model

import "time"

const (
	PresenceOnline          = "online"
	PresenceOffline         = "offline"
	PresenceTyping          = "typing"
	PresenceRecordingVoice  = "recording_voice"
	PresenceRecordingVideo  = "recording_video"
	PresenceInCall          = "in_call"
	PresenceHidden          = "hidden"

	PresenceVisibilityAll      = "all"
	PresenceVisibilityContacts = "contacts"
	PresenceVisibilityNobody   = "nobody"

	LastSeenJustNow   = "just_now"
	LastSeenRecently  = "recently"
	LastSeenToday     = "today"
	LastSeenThisWeek  = "this_week"
	LastSeenLongAgo   = "long_ago"

	RecordingKindVoice = "voice"
	RecordingKindVideo = "video"
)

// Legacy presence API (совместимость).
type PresenceResponse struct {
	UserID       string     `json:"user_id"`
	Status       string     `json:"status"`
	LastSeenAt   *time.Time `json:"last_seen_at,omitempty"`
	ShowLastSeen bool       `json:"show_last_seen"`
}

type UpdatePresenceRequest struct {
	Status       *string `json:"status"`
	ShowLastSeen *bool   `json:"show_last_seen"`
}

type TypingRequest struct {
	ChatID string `json:"chat_id"`
	Typing bool   `json:"typing"`
}

type HeartbeatRequest struct {
	DeviceID string `json:"device_id,omitempty"`
}

type TypingChatRequest struct {
	ChatID string `json:"chat_id"`
}

type RecordingPresenceRequest struct {
	ChatID string `json:"chat_id"`
	Kind   string `json:"kind,omitempty"`
}

type PresencePrivacySettings struct {
	OnlineVisibility   string `json:"online_visibility"`
	LastSeenVisibility string `json:"last_seen_visibility"`
}

type UpdatePresencePrivacyRequest struct {
	OnlineVisibility   *string `json:"online_visibility"`
	LastSeenVisibility *string `json:"last_seen_visibility"`
}

type UserPresenceView struct {
	UserID         string   `json:"user_id"`
	Status         string   `json:"status"`
	LastSeenBucket string   `json:"last_seen_bucket,omitempty"`
	InCall         bool     `json:"in_call,omitempty"`
	CallID         string   `json:"call_id,omitempty"`
	ActiveDevices  []string `json:"active_devices,omitempty"`
}

type UsersPresenceResponse struct {
	Users []UserPresenceView `json:"users"`
}

type ChatPresenceView struct {
	ChatID    string            `json:"chat_id"`
	Typing    []string          `json:"typing_user_ids"`
	Recording map[string]string `json:"recording,omitempty"`
}