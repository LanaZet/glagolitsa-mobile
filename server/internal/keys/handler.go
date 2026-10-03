// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package keys

const prekeyLowThreshold = 20

// Handler — HTTP-обработчики Key Service.
type Handler struct {
	Store    Store
	Notifier PrekeyNotifier
}

func NewHandler(store Store) *Handler {
	return &Handler{Store: store, Notifier: NoopPrekeyNotifier{}}
}