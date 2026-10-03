// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package profile

import "glagolitsa/server/internal/model"

type Handler struct {
	Store Store
	Audit AuditRecorder
}

func NewHandler(store Store) *Handler {
	return &Handler{Store: store}
}

func (h *Handler) userOrError(userID string) (model.User, error) {
	return h.Store.GetProfile(userID)
}