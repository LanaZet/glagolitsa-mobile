-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Key Service: transparency log, device attestation metadata.

CREATE TABLE IF NOT EXISTS key_change_events (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    identity_key_hash BYTEA NOT NULL,
    signed_prekey_id INT,
    prev_event_hash BYTEA,
    event_hash BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_key_change_events_account_created
    ON key_change_events (account_id, created_at ASC);

CREATE INDEX IF NOT EXISTS idx_key_change_events_device_created
    ON key_change_events (device_id, created_at DESC);

CREATE TABLE IF NOT EXISTS device_key_attestations (
    device_id TEXT PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
    confirming_device_id TEXT NOT NULL,
    signature BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);