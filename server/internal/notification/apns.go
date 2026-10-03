// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package notification

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// APNsAdapter — iOS data/alert push with privacy-safe payload (no message body).
// Env: APNS_KEY_ID, APNS_TEAM_ID, APNS_BUNDLE_ID, APNS_KEY_FILE or APNS_KEY_PEM,
// APNS_PRODUCTION=1 for api.push.apple.com (default sandbox).
type APNsAdapter struct {
	keyID    string
	teamID   string
	bundleID string
	key      *ecdsa.PrivateKey
	host     string
	client   *http.Client
	mu       sync.Mutex
	jwt      string
	jwtExp   time.Time
}

type APNsConfig struct {
	KeyID      string
	TeamID     string
	BundleID   string
	KeyPEM     []byte
	Production bool
}

func NewAPNsAdapterFromEnv() (*APNsAdapter, error) {
	keyID := strings.TrimSpace(os.Getenv("APNS_KEY_ID"))
	teamID := strings.TrimSpace(os.Getenv("APNS_TEAM_ID"))
	bundleID := strings.TrimSpace(os.Getenv("APNS_BUNDLE_ID"))
	if keyID == "" || teamID == "" || bundleID == "" {
		return nil, fmt.Errorf("APNS_KEY_ID, APNS_TEAM_ID, APNS_BUNDLE_ID required")
	}
	var pemBytes []byte
	if inline := strings.TrimSpace(os.Getenv("APNS_KEY_PEM")); inline != "" {
		pemBytes = []byte(inline)
	} else if path := strings.TrimSpace(os.Getenv("APNS_KEY_FILE")); path != "" {
		raw, err := os.ReadFile(path)
		if err != nil {
			return nil, err
		}
		pemBytes = raw
	} else {
		return nil, fmt.Errorf("APNS_KEY_PEM or APNS_KEY_FILE required")
	}
	return NewAPNsAdapter(APNsConfig{
		KeyID:      keyID,
		TeamID:     teamID,
		BundleID:   bundleID,
		KeyPEM:     pemBytes,
		Production: os.Getenv("APNS_PRODUCTION") == "1",
	})
}

func NewAPNsAdapter(cfg APNsConfig) (*APNsAdapter, error) {
	key, err := parseAPNsPKCS8(cfg.KeyPEM)
	if err != nil {
		return nil, err
	}
	host := "https://api.sandbox.push.apple.com"
	if cfg.Production {
		host = "https://api.push.apple.com"
	}
	return &APNsAdapter{
		keyID:    cfg.KeyID,
		teamID:   cfg.TeamID,
		bundleID: cfg.BundleID,
		key:      key,
		host:     host,
		client:   &http.Client{Timeout: 10 * time.Second},
	}, nil
}

func parseAPNsPKCS8(pemBytes []byte) (*ecdsa.PrivateKey, error) {
	block, _ := pem.Decode(pemBytes)
	if block == nil {
		return nil, fmt.Errorf("apns: invalid PEM")
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("apns: parse key: %w", err)
	}
	key, ok := parsed.(*ecdsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("apns: key is not ECDSA")
	}
	return key, nil
}

func (a *APNsAdapter) Platform() string { return PlatformIOS }

func (a *APNsAdapter) Send(ctx context.Context, token PushToken, data map[string]string, priority string, silent bool) error {
	_ = silent
	// content-available wake; optional generic alert for high-priority types
	// so iOS may surface something if app cannot run (still no message body).
	aps := map[string]any{
		"content-available": 1,
	}
	if priority == PriorityHigh || priority == PriorityVoIP {
		title, body := SanitizeUserVisibleText(data["type"], DefaultPreferences(""))
		if title != "" {
			aps["alert"] = map[string]string{"title": title, "body": body}
			aps["sound"] = "default"
		}
	}
	if priority == PriorityVoIP {
		// VoIP push uses separate topic; data path still privacy-safe.
		aps["content-available"] = 1
	}
	payload := map[string]any{
		"aps":  aps,
		"data": data,
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	bearer, err := a.bearerJWT()
	if err != nil {
		return err
	}
	url := fmt.Sprintf("%s/3/device/%s", a.host, token.Token)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(raw))
	if err != nil {
		return err
	}
	req.Header.Set("authorization", "bearer "+bearer)
	req.Header.Set("apns-topic", a.bundleID)
	req.Header.Set("apns-push-type", "background")
	if priority == PriorityHigh || priority == PriorityVoIP {
		req.Header.Set("apns-push-type", "alert")
		req.Header.Set("apns-priority", "10")
	} else {
		req.Header.Set("apns-priority", "5")
	}
	if collapse := CollapseKeyForType(data["type"]); collapse != "" {
		req.Header.Set("apns-collapse-id", collapse)
	}
	resp, err := a.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusGone || resp.StatusCode == http.StatusBadRequest {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
		return fmt.Errorf("apns permanent http %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	if resp.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
		return fmt.Errorf("apns http %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	return nil
}

func (a *APNsAdapter) bearerJWT() (string, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.jwt != "" && time.Now().Before(a.jwtExp.Add(-30*time.Second)) {
		return a.jwt, nil
	}
	now := time.Now()
	claims := jwt.MapClaims{
		"iss": a.teamID,
		"iat": now.Unix(),
	}
	tok := jwt.NewWithClaims(jwt.SigningMethodES256, claims)
	tok.Header["kid"] = a.keyID
	signed, err := tok.SignedString(a.key)
	if err != nil {
		return "", err
	}
	a.jwt = signed
	a.jwtExp = now.Add(50 * time.Minute)
	return signed, nil
}
