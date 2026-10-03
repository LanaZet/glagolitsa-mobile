-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

ALTER TABLE webauthn_credentials
    ADD COLUMN IF NOT EXISTS attestation_type TEXT NOT NULL DEFAULT 'none',
    ADD COLUMN IF NOT EXISTS credential_json JSONB;

CREATE TABLE IF NOT EXISTS webauthn_sessions (
    id TEXT PRIMARY KEY,
    user_id UUID,
    username TEXT NOT NULL DEFAULT '',
    purpose TEXT NOT NULL,
    session_json BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS webauthn_sessions_expires_idx
    ON webauthn_sessions (expires_at);
