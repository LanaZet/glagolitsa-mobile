-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Group E2EE: opaque Sender Key ciphertext on the server (no plaintext).

ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS envelope_type SMALLINT,
    ADD COLUMN IF NOT EXISTS ciphertext BYTEA,
    ADD COLUMN IF NOT EXISTS sender_device_id TEXT;