// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package api

import (
	"glagolitsa/server/internal/calling"
	"glagolitsa/server/internal/identity"
	"glagolitsa/server/internal/group"
	"glagolitsa/server/internal/sync"
	"glagolitsa/server/internal/keys"
	"glagolitsa/server/internal/media"
	"glagolitsa/server/internal/messaging"
	"glagolitsa/server/internal/notification"
	"glagolitsa/server/internal/presence"
	"glagolitsa/server/internal/profile"
	"glagolitsa/server/internal/store"
)

// MonolithStore — композитный контракт: один PostgresStore на все домены.
type MonolithStore interface {
	store.Store
	identity.Store
	profile.Store
	messaging.Store
	keys.Store
	calling.Store
	notification.Store
	media.Store
	group.Store
	sync.Store
}

// Проверка на этапе компиляции: store.Store покрывает все доменные интерфейсы.
var (
	_ identity.Store  = (MonolithStore)(nil)
	_ profile.Store   = (MonolithStore)(nil)
	_ messaging.Store = (MonolithStore)(nil)
	_ keys.Store      = (MonolithStore)(nil)
	_ calling.Store        = (MonolithStore)(nil)
	_ presence.PrivacyStore = (MonolithStore)(nil)
	_ presence.SocialGraph  = (MonolithStore)(nil)
	_ notification.Store    = (MonolithStore)(nil)
	_ media.Store           = (MonolithStore)(nil)
	_ group.Store           = (MonolithStore)(nil)
	_ sync.Store            = (MonolithStore)(nil)
)