-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- NIST 800-63B-4 saved recovery codes + trusted-device approval tickets.
-- Server stores only hashes; raw recovery key / ticket never persist.

CREATE TABLE IF NOT EXISTS recovery_tickets (
    id TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    purpose TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS recovery_tickets_user_idx
    ON recovery_tickets (user_id);

CREATE TABLE IF NOT EXISTS trusted_recovery_challenges (
    id TEXT PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    approved_at TIMESTAMPTZ,
    approved_by_device_id TEXT,
    ticket_issued BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS trusted_recovery_pending_idx
    ON trusted_recovery_challenges (user_id, expires_at)
    WHERE approved_at IS NULL;
