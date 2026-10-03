// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package auth

import (
	"errors"
	"os"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

var ErrInvalidToken = errors.New("invalid token")

type Claims struct {
	UserID      string `json:"user_id"`
	Username    string `json:"username"`
	DeviceID    string `json:"device_id,omitempty"`
	TrustTier   string `json:"trust_tier,omitempty"`
	AccountRole string `json:"account_role,omitempty"`
	SessionID   string `json:"sid,omitempty"`
	jwt.RegisteredClaims
}

func Secret() []byte {
	if value := os.Getenv("JWT_SECRET"); value != "" {
		return []byte(value)
	}
	return []byte("glagolitsa-dev-secret-change-me")
}

type AccessTokenInput struct {
	UserID      string
	Username    string
	DeviceID    string
	TrustTier   string
	AccountRole string
	SessionID   string
}

func IssueAccessToken(input AccessTokenInput) (string, int, error) {
	sessionID := input.SessionID
	if sessionID == "" {
		sessionID = uuid.NewString()
	}
	trustTier := input.TrustTier
	if trustTier == "" {
		trustTier = "new"
	}
	accountRole := input.AccountRole
	if accountRole == "" {
		accountRole = "user"
	}

	claims := Claims{
		UserID:      input.UserID,
		Username:    input.Username,
		DeviceID:    input.DeviceID,
		TrustTier:   trustTier,
		AccountRole: accountRole,
		SessionID:   sessionID,
		RegisteredClaims: jwt.RegisteredClaims{
			ID:        sessionID,
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(AccessTokenTTL)),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	signed, err := token.SignedString(Secret())
	if err != nil {
		return "", 0, err
	}
	return signed, int(AccessTokenTTL.Seconds()), nil
}

// IssueToken сохраняет совместимость со старым кодом (короткий access).
func IssueToken(userID, username string) (string, error) {
	token, _, err := IssueAccessToken(AccessTokenInput{
		UserID:    userID,
		Username:  username,
		TrustTier: "trusted",
	})
	return token, err
}

func ParseToken(tokenString string) (Claims, error) {
	token, err := jwt.ParseWithClaims(tokenString, &Claims{}, func(token *jwt.Token) (any, error) {
		if token.Method != jwt.SigningMethodHS256 {
			return nil, ErrInvalidToken
		}
		return Secret(), nil
	})
	if err != nil {
		return Claims{}, ErrInvalidToken
	}

	claims, ok := token.Claims.(*Claims)
	if !ok || !token.Valid {
		return Claims{}, ErrInvalidToken
	}
	return *claims, nil
}
