// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

import (
	"context"
	"fmt"
	"log/slog"
	"os"
)

// PushAdapter — FCM / APNs / UnifiedPush / Web.
type PushAdapter interface {
	Platform() string
	Send(ctx context.Context, token PushToken, data map[string]string, priority string, silent bool) error
}

type LogPushAdapter struct {
	PlatformName string
}

func (a LogPushAdapter) Platform() string { return a.PlatformName }

func (a LogPushAdapter) Send(ctx context.Context, token PushToken, data map[string]string, priority string, silent bool) error {
	_ = ctx
	// Service layer logs delivery with token_id; keep adapter log for log-only mode.
	slog.Info("notification_push_adapter",
		"adapter", a.PlatformName,
		"user_id", token.UserID,
		"device_id", token.DeviceID,
		"type", data["type"],
		"call_id", data["call_id"],
		"priority", priority,
		"silent", silent,
	)
	return nil
}

type ErrorPushAdapter struct {
	PlatformName string
	Err          error
}

func (a ErrorPushAdapter) Platform() string { return a.PlatformName }

func (a ErrorPushAdapter) Send(ctx context.Context, token PushToken, data map[string]string, priority string, silent bool) error {
	_, _, _, _ = ctx, token, priority, silent
	if a.Err != nil {
		return a.Err
	}
	return fmt.Errorf("%s push adapter is not configured", a.PlatformName)
}

func AdaptersFromEnv() map[string]PushAdapter {
	adapters := map[string]PushAdapter{
		PlatformAndroid:     LogPushAdapter{PlatformName: PlatformAndroid},
		PlatformIOS:         LogPushAdapter{PlatformName: PlatformIOS},
		PlatformWeb:         LogPushAdapter{PlatformName: PlatformWeb},
		PlatformUnifiedPush: NewUnifiedPushAdapter(),
	}
	if projectID := os.Getenv("FCM_PROJECT_ID"); projectID != "" {
		credentialsJSON, credentialsErr := loadFCMCredentialsJSON()
		if credentialsErr != nil {
			slog.Warn("fcm_adapter_disabled", "error", credentialsErr)
			adapters[PlatformAndroid] = ErrorPushAdapter{PlatformName: PlatformAndroid, Err: credentialsErr}
		} else if fcm, err := NewFCMAdapter(FCMConfig{
			ProjectID:       projectID,
			CredentialsJSON: credentialsJSON,
		}); err == nil {
			adapters[PlatformAndroid] = fcm
		} else {
			slog.Warn("fcm_adapter_disabled", "error", err)
			adapters[PlatformAndroid] = ErrorPushAdapter{PlatformName: PlatformAndroid, Err: err}
		}
	}
	if apns, err := NewAPNsAdapterFromEnv(); err == nil {
		adapters[PlatformIOS] = apns
		slog.Info("apns_adapter_enabled")
	} else if os.Getenv("APNS_KEY_ID") != "" {
		slog.Warn("apns_adapter_disabled", "error", err)
	}
	return adapters
}
