-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Track live devices so abandoned reinstall "zombies" can be purged safely.
ALTER TABLE devices
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ;

UPDATE devices
SET last_seen_at = COALESCE(last_seen_at, updated_at, created_at, NOW())
WHERE last_seen_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_devices_status_last_seen
    ON devices (device_status, last_seen_at);

-- Orphan envelopes for already-revoked devices poison multi-device fan-out forever.
DELETE FROM messages_queue mq
USING devices d
WHERE d.mailbox_token IS NOT NULL
  AND mq.mailbox_token = d.mailbox_token
  AND d.device_status = 'revoked';

DELETE FROM messages_queue mq
USING devices d
WHERE d.mailbox_token_previous IS NOT NULL
  AND mq.mailbox_token = d.mailbox_token_previous
  AND d.device_status = 'revoked';
