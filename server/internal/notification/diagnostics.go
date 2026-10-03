// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

import "glagolitsa/server/internal/diagnostics"

// PushDiagnostics — Mattermost push-proxy readiness analogue without secrets.
// Reports which OSPNS backends are live (fcm/apns vs log stub).
func (s *Service) PushDiagnostics() diagnostics.PushInfo {
	info := diagnostics.PushInfo{
		Android:     "log",
		IOS:         "log",
		Web:         "log",
		UnifiedPush: "log",
		Privacy:     "opaque_wake_only",
	}
	if s == nil || s.Adapters == nil {
		return info
	}
	if a, ok := s.Adapters[PlatformAndroid]; ok {
		info.Android = adapterMode(a)
	}
	if a, ok := s.Adapters[PlatformIOS]; ok {
		info.IOS = adapterMode(a)
	}
	if a, ok := s.Adapters[PlatformWeb]; ok {
		info.Web = adapterMode(a)
	}
	if a, ok := s.Adapters[PlatformUnifiedPush]; ok {
		info.UnifiedPush = adapterMode(a)
	}
	return info
}

func adapterMode(a PushAdapter) string {
	switch a.(type) {
	case *FCMAdapter:
		return "fcm"
	case *APNsAdapter:
		return "apns"
	case *UnifiedPushAdapter:
		return "simple_push"
	case LogPushAdapter:
		return "log"
	case ErrorPushAdapter:
		return "error"
	default:
		// Unknown concrete type — still never expose token material.
		if a == nil {
			return "none"
		}
		return "custom"
	}
}
