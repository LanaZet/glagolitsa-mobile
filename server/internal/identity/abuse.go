// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package identity

import (
	"crypto/rand"
	"encoding/base64"
	"net/http"
	"time"

	"github.com/google/uuid"

	"glagolitsa/server/internal/httpx"
	"glagolitsa/server/internal/model"
	"glagolitsa/server/internal/store"
)

func (h *Handler) CreatePowChallenge(w http.ResponseWriter, r *http.Request) {
	buf := make([]byte, 24)
	if _, err := rand.Read(buf); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "entropy error")
		return
	}
	challenge := base64.RawURLEncoding.EncodeToString(buf)
	challengeID := uuid.NewString()
	// 14 — ~1–5 с на телефоне; 16 — защита от ботов, дольше на слабых CPU. Переопределение: REGISTRATION_POW_DIFFICULTY.
	difficulty := registrationPowDifficulty()
	expires := store.NowUTC().Add(10 * time.Minute)

	if err := h.Store.CreatePowChallenge(challengeID, challenge, clientIPHash(r), difficulty, expires); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}

	httpx.WriteJSON(w, http.StatusOK, model.PowChallengeResponse{
		ChallengeID: challengeID,
		Challenge:   challenge,
		Difficulty:  difficulty,
		ExpiresAt:   expires.Format(time.RFC3339),
	})
}
