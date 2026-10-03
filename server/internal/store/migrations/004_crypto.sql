-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- E2E crypto: публичные ключи устройств и one-time prekeys (этап 2).
-- Сервер хранит только public material; приватные ключи не покидают клиент.

CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    registration_id INT NOT NULL,
    identity_public_key BYTEA NOT NULL,
    signed_prekey_id INT NOT NULL,
    signed_prekey_public_key BYTEA NOT NULL,
    signed_prekey_signature BYTEA NOT NULL,
    signed_prekey_created_at BIGINT NOT NULL,
    pq_prekey_id INT NOT NULL,
    pq_public_material BYTEA NOT NULL,
    pq_prekey_signature BYTEA NOT NULL,
    pq_prekey_created_at BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_devices_account_id
    ON devices (account_id);

CREATE TABLE IF NOT EXISTS prekeys (
    device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    prekey_id INT NOT NULL,
    public_key BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (device_id, prekey_id)
);

CREATE INDEX IF NOT EXISTS idx_prekeys_device_id
    ON prekeys (device_id);