-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Dual media path:
--   encrypted — DM/group e2e opaque blobs (default, unchanged)
--   open      — channel plaintext photos (membership-gated download)

ALTER TABLE media_files
    ADD COLUMN IF NOT EXISTS content_mode TEXT NOT NULL DEFAULT 'encrypted'
        CHECK (content_mode IN ('encrypted', 'open'));

CREATE INDEX IF NOT EXISTS idx_media_files_chat_open
    ON media_files (chat_id)
    WHERE content_mode = 'open' AND deleted_at IS NULL;
