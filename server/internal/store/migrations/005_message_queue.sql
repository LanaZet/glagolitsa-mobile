-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- E2EE relay: opaque ciphertext only, удаляется после ACK (этап 3.1).

ALTER TABLE devices
    ADD COLUMN IF NOT EXISTS mailbox_token TEXT;

UPDATE devices
SET mailbox_token = device_id
WHERE mailbox_token IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_mailbox_token
    ON devices (mailbox_token);

CREATE TABLE IF NOT EXISTS messages_queue (
    envelope_id UUID PRIMARY KEY,
    mailbox_token TEXT NOT NULL,
    sender_account_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender_device_id TEXT NOT NULL,
    chat_id UUID REFERENCES chats(id) ON DELETE SET NULL,
    envelope_type SMALLINT NOT NULL,
    ciphertext BYTEA NOT NULL,
    size_bucket INT NOT NULL,
    client_message_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '30 days')
);

CREATE INDEX IF NOT EXISTS idx_messages_queue_mailbox_pending
    ON messages_queue (mailbox_token, created_at);

CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_queue_dedup
    ON messages_queue (mailbox_token, sender_device_id, client_message_id)
    WHERE client_message_id IS NOT NULL;