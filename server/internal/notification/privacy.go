// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

import (
	"fmt"
	"strings"
)

// forbiddenPayloadKeys — never send to OSPNS (arXiv 2407.10589 leak classes).
// Covers plaintext content, display metadata, and crypto material.
var forbiddenPayloadKeys = []string{
	"body", "text", "message", "content", "preview", "ciphertext",
	"sender_name", "sender", "name", "title", "caller_name", "avatar",
	"phone", "email", "password", "key", "private_key", "session_key",
	"loc_args", "loc_key", // Telegram-style template args must not appear in our PNS
}

// allowedPayloadKeys — push-to-sync / event_id_only style (Signal + Matrix privacy).
// Unknown keys are rejected so new fields cannot accidentally leak metadata.
var allowedPayloadKeys = map[string]struct{}{
	"schema":        {},
	"type":          {}, // wake type (e.g. incoming_call)
	"envelope_id":   {}, // optional; prefer pure wake without it
	"mailbox_token": {},
	"device_id":     {},
	"device_hint":   {},
	"remaining":     {},
	// Call wake-only: opaque call_id + collapse. No caller_id/name/room/token.
	"call_id":     {},
	"collapse_id": {},
}

// PrivacyGuard — validates OSPNS payloads stay content-free.
type PrivacyGuard struct{}

func (PrivacyGuard) ValidatePayload(payload map[string]any) error {
	for key, value := range payload {
		lower := strings.ToLower(strings.TrimSpace(key))
		for _, forbidden := range forbiddenPayloadKeys {
			if lower == forbidden || strings.Contains(lower, forbidden) {
				return fmt.Errorf("privacy guard: forbidden payload key %q", key)
			}
		}
		if _, ok := allowedPayloadKeys[lower]; !ok {
			return fmt.Errorf("privacy guard: unknown payload key %q (allowlist only)", key)
		}
		if text, ok := value.(string); ok && len(text) > 512 {
			return fmt.Errorf("privacy guard: payload value for %q is too large", key)
		}
	}
	return nil
}

// SanitizeUserVisibleText — default local/system copy without content.
// Used by clients; server may use for rare alert-style platforms.
func SanitizeUserVisibleText(notificationType string, prefs Preferences) (title, body string) {
	switch notificationType {
	case TypeIncomingCall:
		if !prefs.CallsEnabled {
			return "", ""
		}
		return "Глаголица", "Входящий звонок"
	case TypeNewMessage, TypeMessageSync:
		if !prefs.MessagesEnabled {
			return "", ""
		}
		return "Глаголица", "Новое сообщение"
	case TypeMissedCall:
		return "Глаголица", "Пропущенный звонок"
	case TypeDeviceAdded:
		return "Глаголица", "Новое устройство"
	case TypeSecurityAlert, TypeKeyChanged:
		return "Глаголица", "Событие безопасности"
	default:
		return "Глаголица", "Новое уведомление"
	}
}
