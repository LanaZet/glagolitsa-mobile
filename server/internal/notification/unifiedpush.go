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
	"strings"
	"time"
)

// UnifiedPushAdapter — Matrix/Telegram simple-push style wake.
// Token is an HTTPS endpoint (distributor). Server PUTs an opaque JSON body;
// content is never included (client fetches mailbox from Glagolitsa API).
// Spec reference: UnifiedPush + Telegram token_type=4 simple push.
type UnifiedPushAdapter struct {
	client *http.Client
}

func NewUnifiedPushAdapter() *UnifiedPushAdapter {
	return &UnifiedPushAdapter{
		client: &http.Client{Timeout: 10 * time.Second},
	}
}

func (a *UnifiedPushAdapter) Platform() string { return PlatformUnifiedPush }

func (a *UnifiedPushAdapter) Send(ctx context.Context, token PushToken, data map[string]string, priority string, silent bool) error {
	endpoint := strings.TrimSpace(token.Token)
	if endpoint == "" {
		return fmt.Errorf("unifiedpush: empty endpoint")
	}
	if !strings.HasPrefix(endpoint, "https://") {
		return fmt.Errorf("unifiedpush: endpoint must be https")
	}
	_ = priority
	_ = silent

	// Opaque wake only — no body/name/ciphertext (arXiv push-leak mitigation).
	payload := map[string]string{
		"type":   data["type"],
		"schema": data["schema"],
	}
	if payload["schema"] == "" {
		payload["schema"] = "1"
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, endpoint, bytes.NewReader(raw))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	// Telegram simple-push also accepts version=N form; JSON is fine for ntfy/UP.
	resp, err := a.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
		return fmt.Errorf("unifiedpush http %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	return nil
}
