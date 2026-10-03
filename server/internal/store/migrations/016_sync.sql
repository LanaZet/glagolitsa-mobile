-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Sync Service: append-only event log + device cursors + snapshots + offline queue.
-- Server stores opaque ciphertext/metadata only — never plaintext or keys.

CREATE TABLE IF NOT EXISTS sync_events (
    event_id BIGSERIAL PRIMARY KEY,
    scope_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    chat_id UUID REFERENCES chats(id) ON DELETE CASCADE,
    message_id TEXT,
    operation TEXT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    ciphertext BYTEA,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    actor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sync_events_event_id
    ON sync_events (event_id);

CREATE INDEX IF NOT EXISTS idx_sync_events_scope_user
    ON sync_events (scope_user_id, event_id)
    WHERE scope_user_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_sync_events_chat
    ON sync_events (chat_id, event_id)
    WHERE chat_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS sync_devices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    label TEXT,
    last_acked_event_id BIGINT NOT NULL DEFAULT 0,
    snapshot_event_id BIGINT,
    sync_profile TEXT NOT NULL DEFAULT 'balanced',
    registered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, device_id)
);

CREATE TABLE IF NOT EXISTS sync_snapshots (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_id BIGINT NOT NULL,
    snapshot_data BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sync_snapshots_user_event
    ON sync_snapshots (user_id, event_id DESC);

CREATE TABLE IF NOT EXISTS sync_offline_queue (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    client_event_id TEXT NOT NULL,
    operation TEXT NOT NULL,
    chat_id UUID,
    message_id TEXT,
    version INT NOT NULL DEFAULT 1,
    ciphertext BYTEA,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'processing', 'applied', 'failed')),
    attempts INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, device_id, client_event_id)
);

CREATE INDEX IF NOT EXISTS idx_sync_offline_queue_retry
    ON sync_offline_queue (status, next_retry_at)
    WHERE status IN ('pending', 'failed');