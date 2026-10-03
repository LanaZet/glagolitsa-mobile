-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Metadata v1: sealed-sender queue, rotatable mailbox tokens (этап 5).

ALTER TABLE devices
    ADD COLUMN IF NOT EXISTS mailbox_token_previous TEXT,
    ADD COLUMN IF NOT EXISTS mailbox_previous_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS mailbox_rotated_at TIMESTAMPTZ;

-- Убираем метаданные отправителя из очереди — снаружи только mailbox_token + ciphertext.
ALTER TABLE messages_queue DROP COLUMN IF EXISTS sender_account_id;
ALTER TABLE messages_queue DROP COLUMN IF EXISTS sender_device_id;
ALTER TABLE messages_queue DROP COLUMN IF EXISTS chat_id;
ALTER TABLE messages_queue DROP COLUMN IF EXISTS client_message_id;

DROP INDEX IF EXISTS idx_messages_queue_dedup;

-- Отвязать mailbox_token от device_id (ротируемые opaque tokens).
UPDATE devices
SET mailbox_token = gen_random_uuid()::text
WHERE mailbox_token IS NULL OR mailbox_token = device_id;