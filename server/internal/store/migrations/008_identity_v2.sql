-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Identity v2: Profile split, sessions, device trust, recovery, abuse, audit.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS trust_tier TEXT NOT NULL DEFAULT 'trusted',
    ADD COLUMN IF NOT EXISTS account_status TEXT NOT NULL DEFAULT 'active',
    ADD COLUMN IF NOT EXISTS recovery_key_hash TEXT,
    ADD COLUMN IF NOT EXISTS recovery_key_hint TEXT;

CREATE TABLE IF NOT EXISTS profiles (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    username TEXT UNIQUE,
    display_name TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT '',
    bio TEXT NOT NULL DEFAULT '',
    avatar_url TEXT NOT NULL DEFAULT '',
    presence TEXT NOT NULL DEFAULT 'online',
    nickname TEXT NOT NULL DEFAULT '',
    position TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO profiles (
    user_id, username, display_name, status, bio,
    avatar_url, presence, nickname, position
)
SELECT
    id, username, display_name, status, bio,
    avatar_url, presence, nickname, position
FROM users
ON CONFLICT (user_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL DEFAULT '',
    refresh_token_hash BYTEA NOT NULL,
    replaced_by UUID REFERENCES sessions(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_sessions_refresh_hash
    ON sessions (refresh_token_hash);

CREATE INDEX IF NOT EXISTS idx_sessions_user_active
    ON sessions (user_id, expires_at)
    WHERE revoked_at IS NULL;

ALTER TABLE devices
    ADD COLUMN IF NOT EXISTS device_status TEXT NOT NULL DEFAULT 'active',
    ADD COLUMN IF NOT EXISTS platform TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS model_hint TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS attestation_level TEXT NOT NULL DEFAULT 'none',
    ADD COLUMN IF NOT EXISTS trust_score INT NOT NULL DEFAULT 50,
    ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS confirmed_by_device_id TEXT;

UPDATE devices SET device_status = 'active' WHERE device_status IS NULL OR device_status = '';

CREATE TABLE IF NOT EXISTS webauthn_credentials (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL DEFAULT '',
    credential_id BYTEA NOT NULL,
    public_key BYTEA NOT NULL,
    sign_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_webauthn_credential_id
    ON webauthn_credentials (credential_id);

CREATE TABLE IF NOT EXISTS registration_challenges (
    id UUID PRIMARY KEY,
    challenge TEXT NOT NULL UNIQUE,
    difficulty INT NOT NULL DEFAULT 18,
    solution TEXT,
    client_ip_hash TEXT,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS account_reputation (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    abuse_reports_count INT NOT NULL DEFAULT 0,
    rate_limit_hits_7d INT NOT NULL DEFAULT 0,
    trusted_contacts_count INT NOT NULL DEFAULT 0,
    successful_conversations_count INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS audit_events (
    id UUID PRIMARY KEY,
    event_type TEXT NOT NULL,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    device_id TEXT,
    coarse_ip_hash TEXT,
    risk_level TEXT NOT NULL DEFAULT 'low',
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_events_user_created
    ON audit_events (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_events_type_created
    ON audit_events (event_type, created_at DESC);