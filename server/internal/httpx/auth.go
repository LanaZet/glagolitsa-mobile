// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package httpx

import (
	"context"
	"net/http"
	"strings"

	"glagolitsa/server/internal/auth"
)

type contextKey string

const userClaimsKey contextKey = "userClaims"

func WithAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		if header == "" || !strings.HasPrefix(header, "Bearer ") {
			WriteError(w, http.StatusUnauthorized, "missing bearer token")
			return
		}

		claims, err := auth.ParseToken(strings.TrimPrefix(header, "Bearer "))
		if err != nil {
			WriteError(w, http.StatusUnauthorized, "invalid token")
			return
		}

		ctx := context.WithValue(r.Context(), userClaimsKey, claims)
		next(w, r.WithContext(ctx))
	}
}

func ClaimsFromContext(r *http.Request) (auth.Claims, bool) {
	claims, ok := r.Context().Value(userClaimsKey).(auth.Claims)
	return claims, ok
}