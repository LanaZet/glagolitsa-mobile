-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Messaging v2: event log, delivery metadata, soft delete, presence, abuse counters.

ALTER TABLE chats
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS chat_events (
    id UUID PRIMARY KEY,
    chat_id UUID REFERENCES chats(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    actor_id UUID,
    entity_id TEXT,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chat_events_chat_created
    ON chat_events (chat_id, created_at ASC);

CREATE INDEX IF NOT EXISTS idx_chat_events_created
    ON chat_events (created_at ASC);

CREATE TABLE IF NOT EXISTS envelope_delivery (
    envelope_id UUID PRIMARY KEY,
    recipient_account_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    mailbox_token TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'queued',
    size_bucket INT NOT NULL DEFAULT 0,
    queued_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    fetched_at TIMESTAMPTZ,
    acked_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_envelope_delivery_recipient_state
    ON envelope_delivery (recipient_account_id, state, queued_at DESC);

CREATE TABLE IF NOT EXISTS user_presence (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'offline',
    last_seen_at TIMESTAMPTZ,
    show_last_seen BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS messaging_limits (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    relay_envelopes_24h INT NOT NULL DEFAULT 0,
    groups_created_24h INT NOT NULL DEFAULT 0,
    relay_window_start TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    groups_window_start TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);