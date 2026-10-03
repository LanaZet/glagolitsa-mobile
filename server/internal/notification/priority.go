// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

// PriorityForType — Firebase 2025: HIGH only for time-sensitive paths that
// produce a user-visible notification (or VoIP). Silent housekeeping stays NORMAL
// so FCM does not deprioritize the app over a 7-day pattern.
//
// arXiv 2407.10589 ("The Medium is the Message"): never put content in the OSPNS
// payload; priority is independent of payload privacy.
func PriorityForType(notificationType string) string {
	switch notificationType {
	case TypeNewMessage, TypeMessageSync:
		// Client shows local system notification after wake+fetch+decrypt.
		return PriorityHigh
	case TypeIncomingCall:
		return PriorityVoIP
	case TypeMissedCall, TypeDeviceAdded, TypeSecurityAlert, TypeKeyChanged, TypeGroupInvite:
		return PriorityHigh
	case TypePrekeysLow:
		// Background key replenish — not user-visible → NORMAL.
		return PriorityNormal
	default:
		return PriorityNormal
	}
}

// IsDataOnlyType — no FCM/APNs "notification" block; app handles UI after decrypt.
func IsDataOnlyType(notificationType string) bool {
	switch notificationType {
	case TypeNewMessage, TypeMessageSync, TypePrekeysLow:
		return true
	default:
		// Calls may still be data-only with local full-screen UI; keep data-only.
		return true
	}
}

// CollapseKeyForType — coalesce burst wakes (WA/Signal pattern).
func CollapseKeyForType(notificationType string) string {
	switch notificationType {
	case TypeNewMessage, TypeMessageSync:
		return "glag_msg_wake"
	case TypeIncomingCall:
		return "glag_call"
	default:
		return ""
	}
}
