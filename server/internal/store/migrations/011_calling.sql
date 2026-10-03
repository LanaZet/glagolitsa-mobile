-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Calling Service: call metadata, participants, encrypted key offers. No media, no keys in plaintext.

CREATE TABLE IF NOT EXISTS calls (
    id UUID PRIMARY KEY,
    caller_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    callee_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    call_type TEXT NOT NULL DEFAULT 'audio',
    status TEXT NOT NULL DEFAULT 'ringing',
    livekit_room_id TEXT NOT NULL,
    caller_device_id TEXT NOT NULL DEFAULT '',
    low_bandwidth_mode BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    accepted_at TIMESTAMPTZ,
    connected_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    duration_sec INT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_calls_caller_created
    ON calls (caller_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_calls_callee_created
    ON calls (callee_id, created_at DESC);

CREATE TABLE IF NOT EXISTS call_participants (
    call_id UUID NOT NULL REFERENCES calls(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL DEFAULT '',
    joined_at TIMESTAMPTZ,
    left_at TIMESTAMPTZ,
    PRIMARY KEY (call_id, user_id, device_id)
);

CREATE TABLE IF NOT EXISTS call_key_offers (
    id UUID PRIMARY KEY,
    call_id UUID NOT NULL REFERENCES calls(id) ON DELETE CASCADE,
    source_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_device_id TEXT NOT NULL,
    target_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_device_id TEXT NOT NULL,
    encrypted_key BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (call_id, source_device_id, target_device_id)
);

CREATE INDEX IF NOT EXISTS idx_call_key_offers_target
    ON call_key_offers (call_id, target_user_id, target_device_id);