// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package relayauth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"os"
	"strings"

	"glagolitsa/server/internal/auth"
)

const deliveryTokenVersion = "delivery-token-v1:"

func DeliveryTokenForMailbox(mailboxToken string) string {
	mailboxToken = strings.TrimSpace(mailboxToken)
	if mailboxToken == "" {
		return ""
	}
	mac := hmac.New(sha256.New, deliveryTokenSecret())
	_, _ = mac.Write([]byte(deliveryTokenVersion))
	_, _ = mac.Write([]byte(mailboxToken))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

func ValidateDeliveryToken(mailboxToken, token string) bool {
	expected := DeliveryTokenForMailbox(mailboxToken)
	token = strings.TrimSpace(token)
	if expected == "" || token == "" {
		return false
	}
	return hmac.Equal([]byte(expected), []byte(token))
}

func deliveryTokenSecret() []byte {
	if value := os.Getenv("DELIVERY_TOKEN_SECRET"); value != "" {
		return []byte(value)
	}
	return auth.Secret()
}
