// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

import "time"

const (
	PlatformIOS         = "ios"
	PlatformAndroid     = "android"
	PlatformWeb         = "web"
	PlatformUnifiedPush = "unifiedpush"

	TypeNewMessage    = "new_message"
	TypeMessageSync   = "message_sync"
	TypeIncomingCall  = "incoming_call"
	TypeMissedCall    = "missed_call"
	TypeGroupInvite   = "group_invite"
	TypeSecurityAlert = "security_alert"
	TypeKeyChanged    = "key_changed"
	TypeDeviceAdded   = "device_added"
	TypePrekeysLow    = "prekeys.low"

	PriorityNormal = "normal"
	PriorityHigh   = "high"
	PriorityVoIP   = "voip"

	TokenStatusActive  = "active"
	TokenStatusInvalid = "invalid"
	TokenStatusRevoked = "revoked"

	DeliverySent    = "sent"
	DeliveryFailed  = "failed"
	DeliverySkipped = "skipped"
	DeliveryQueued  = "queued"

	// PayloadSchemaV1 — opaque wake schema (Signal push-to-sync).
	PayloadSchemaV1 = "1"
)

// PushToken — зарегистрированный device push token.
type PushToken struct {
	ID            string     `json:"id"`
	UserID        string     `json:"user_id"`
	DeviceID      string     `json:"device_id"`
	Platform      string     `json:"platform"`
	Token         string     `json:"-"`
	TokenStatus   string     `json:"token_status"`
	LastSuccessAt *time.Time `json:"last_success_at,omitempty"`
	LastFailureAt *time.Time `json:"last_failure_at,omitempty"`
	FailureCount  int        `json:"failure_count"`
	CreatedAt     time.Time  `json:"created_at"`
	UpdatedAt     time.Time  `json:"updated_at"`
	RevokedAt     *time.Time `json:"revoked_at,omitempty"`
}

// Preferences — настройки уведомлений (без содержимого сообщений).
type Preferences struct {
	UserID             string    `json:"user_id"`
	MessagesEnabled    bool      `json:"messages_enabled"`
	CallsEnabled       bool      `json:"calls_enabled"`
	NewDeviceEnabled   bool      `json:"new_device_enabled"`
	ShowSenderName     bool      `json:"show_sender_name"`
	ShowMessagePreview bool      `json:"show_message_preview"`
	BadgeEnabled       bool      `json:"badge_enabled"`
	UpdatedAt          time.Time `json:"updated_at"`
}

// DefaultPreferences — приватный мессенджер: без текста и имён по умолчанию.
func DefaultPreferences(userID string) Preferences {
	return Preferences{
		UserID:             userID,
		MessagesEnabled:    true,
		CallsEnabled:       true,
		NewDeviceEnabled:   true,
		ShowSenderName:     false,
		ShowMessagePreview: false,
		BadgeEnabled:       true,
		UpdatedAt:          time.Now().UTC(),
	}
}

type RegisterTokenRequest struct {
	Platform string `json:"platform"`
	Token    string `json:"token"`
	DeviceID string `json:"device_id"`
}

type RegisterTokenResponse struct {
	ID       string `json:"id"`
	Platform string `json:"platform"`
	DeviceID string `json:"device_id"`
	Status   string `json:"status"`
}

type UpdatePreferencesRequest struct {
	MessagesEnabled    *bool `json:"messages_enabled,omitempty"`
	CallsEnabled       *bool `json:"calls_enabled,omitempty"`
	NewDeviceEnabled   *bool `json:"new_device_enabled,omitempty"`
	ShowSenderName     *bool `json:"show_sender_name,omitempty"`
	ShowMessagePreview *bool `json:"show_message_preview,omitempty"`
	BadgeEnabled       *bool `json:"badge_enabled,omitempty"`
}

// SendRequest — internal API: opaque payload only (arXiv: no content in OSPNS).
type SendRequest struct {
	UserID   string         `json:"user_id"`
	Type     string         `json:"type"`
	Payload  map[string]any `json:"payload"`
	Priority string         `json:"priority"`
	// OnlyDeviceIDs — if non-empty, only these device tokens are targeted.
	OnlyDeviceIDs []string `json:"-"`
	// ExcludeDeviceIDs — skip tokens for online/WS-connected devices (P2).
	ExcludeDeviceIDs []string `json:"-"`
	// SkipDebounce — bypass coalesce window (tests / calls).
	SkipDebounce bool `json:"-"`
}

type RetryJob struct {
	ID               string
	UserID           string
	NotificationType string
	Payload          map[string]any
	Priority         string
	Attempts         int
	MaxAttempts      int
	NextAttemptAt    time.Time
	LastError        string
	CreatedAt        time.Time
}
