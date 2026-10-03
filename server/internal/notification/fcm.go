// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

const fcmMessagingScope = "https://www.googleapis.com/auth/firebase.messaging"

type FCMConfig struct {
	ProjectID       string
	CredentialsJSON []byte
}

type FCMAdapter struct {
	projectID   string
	client      *http.Client
	tokenSource oauth2.TokenSource
	send        func(ctx context.Context, token string, message map[string]any) error
}

func NewFCMAdapter(cfg FCMConfig) (*FCMAdapter, error) {
	if strings.TrimSpace(cfg.ProjectID) == "" {
		return nil, fmt.Errorf("FCM_PROJECT_ID is required")
	}
	if len(cfg.CredentialsJSON) == 0 {
		return nil, fmt.Errorf("FCM credentials are required when FCM_PROJECT_ID is set")
	}
	adapter := &FCMAdapter{
		projectID: cfg.ProjectID,
		client:    &http.Client{Timeout: 10 * time.Second},
	}
	creds, err := google.CredentialsFromJSON(context.Background(), cfg.CredentialsJSON, fcmMessagingScope)
	if err != nil {
		return nil, fmt.Errorf("parse FCM service account: %w", err)
	}
	adapter.tokenSource = creds.TokenSource
	adapter.send = adapter.sendHTTPv1
	return adapter, nil
}

func (a *FCMAdapter) Platform() string { return PlatformAndroid }

// Send — data-only FCM message (no notification block = no content leak to Google UI path).
// Priority follows req (Firebase 2025: do not force HIGH on silent housekeeping).
// collapse_key coalesces message wakes.
func (a *FCMAdapter) Send(ctx context.Context, token PushToken, data map[string]string, priority string, silent bool) error {
	_ = silent // data-only always; client builds local UI after decrypt
	androidPriority := "NORMAL"
	if priority == PriorityHigh || priority == PriorityVoIP {
		androidPriority = "HIGH"
	}
	android := map[string]any{
		"priority": androidPriority,
	}
	if collapse := CollapseKeyForType(data["type"]); collapse != "" {
		android["collapse_key"] = collapse
	}
	message := map[string]any{
		"token":   token.Token,
		"data":    data,
		"android": android,
	}
	return a.send(ctx, token.Token, message)
}

func (a *FCMAdapter) sendLogOnly(ctx context.Context, token string, message map[string]any) error {
	_, _ = ctx, token
	data, _ := message["data"].(map[string]string)
	prio := PriorityNormal
	if android, ok := message["android"].(map[string]any); ok {
		if p, ok := android["priority"].(string); ok && strings.EqualFold(p, "HIGH") {
			prio = PriorityHigh
		}
	}
	return LogPushAdapter{PlatformName: PlatformAndroid}.Send(ctx, PushToken{Token: token}, data, prio, true)
}

func (a *FCMAdapter) sendHTTPv1(ctx context.Context, token string, message map[string]any) error {
	accessToken, err := a.accessToken(ctx)
	if err != nil {
		return err
	}
	body, err := json.Marshal(map[string]any{"message": message})
	if err != nil {
		return err
	}
	url := fmt.Sprintf("https://fcm.googleapis.com/v1/projects/%s/messages:send", a.projectID)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+accessToken)
	resp, err := a.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return fmt.Errorf("fcm http %d: %s", resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	return nil
}

func (a *FCMAdapter) accessToken(ctx context.Context) (string, error) {
	if a.tokenSource == nil {
		return "", fmt.Errorf("fcm token source is not configured")
	}
	tok, err := a.tokenSource.Token()
	if err != nil {
		return "", fmt.Errorf("fcm oauth token: %w", err)
	}
	if tok.AccessToken == "" {
		return "", fmt.Errorf("fcm oauth token is empty")
	}
	_ = ctx
	return tok.AccessToken, nil
}

func loadFCMCredentialsJSON() ([]byte, error) {
	if inline := strings.TrimSpace(os.Getenv("FCM_CREDENTIALS_JSON")); inline != "" {
		return []byte(inline), nil
	}
	path := strings.TrimSpace(os.Getenv("FCM_CREDENTIALS_FILE"))
	if path == "" {
		return nil, nil
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read FCM credentials file: %w", err)
	}
	return raw, nil
}
