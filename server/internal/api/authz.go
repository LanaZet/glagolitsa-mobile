// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"net/http"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
)

// Маршруты, доступные до регистрации устройства на сервере (inactive или active без device_id).
var pendingDeviceRoutes = map[string]struct{}{
	"POST /api/devices":                       {},
	"GET /api/users/me":                       {},
	"GET /api/profile/me":                     {},
	"PATCH /api/users/me":                     {},
	"PATCH /api/profile/me":                   {},
	"POST /api/auth/logout":                   {},
	"POST /api/auth/refresh":                  {},
	"POST /api/recovery/setup":                {},
	"GET /api/recovery/status":                {},
	"GET /api/recovery/trusted/pending":       {},
	"POST /api/recovery/trusted/approve":      {},
	"POST /api/auth/webauthn/register/begin":  {},
	"POST /api/auth/webauthn/register/finish": {},
}

// pendingDeviceMessage — единый ответ, пока у аккаунта нет device_id в БД.
const pendingDeviceMessage = "account pending device registration"

func routeKey(r *http.Request) string {
	// Go 1.22+ ServeMux: Pattern уже "METHOD /path" для "POST /api/devices".
	if r.Pattern != "" {
		return r.Pattern
	}
	return r.Method + " " + r.URL.Path
}

func (h *Handler) withAuth(next http.HandlerFunc) http.HandlerFunc {
	return httpx.WithAuth(func(w http.ResponseWriter, r *http.Request) {
		claims, ok := httpx.ClaimsFromContext(r)
		if !ok {
			httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
			return
		}

		if claims.SessionID != "" {
			if _, err := h.store.FindSessionByID(claims.SessionID); err != nil {
				httpx.WriteError(w, http.StatusUnauthorized, "session revoked")
				return
			}
		}

		// Mattermost-style hard floor when client advertises version headers.
		if h.rejectIfClientTooOld(w, r) {
			return
		}

		key := routeKey(r)
		if pendingDeviceRouteAllowed(r, claims.UserID, key) {
			next(w, r)
			return
		}

		account, err := h.store.GetAccountByID(claims.UserID)
		if err != nil {
			httpx.WriteError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		if account.AccountStatus == model.AccountStatusInactive {
			httpx.WriteError(w, http.StatusForbidden, pendingDeviceMessage)
			return
		}

		deviceCount, err := h.store.CountActiveDevices(claims.UserID)
		if err != nil {
			httpx.WriteError(w, http.StatusInternalServerError, "could not verify device registration")
			return
		}
		if deviceCount == 0 {
			httpx.WriteError(w, http.StatusForbidden, pendingDeviceMessage)
			return
		}

		next(w, r)
	})
}

func pendingDeviceRouteAllowed(r *http.Request, userID, key string) bool {
	if _, allowed := pendingDeviceRoutes[key]; allowed {
		return true
	}
	return key == "GET /api/users/{id}/devices" && r.PathValue("id") == userID
}
