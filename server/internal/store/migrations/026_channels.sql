-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Hybrid conversation policies for public/private channels (Matrix-like state on group shell).
-- Product type lives in chats.chat_type = 'channel'; policies in group_settings.

ALTER TABLE group_settings
    ADD COLUMN IF NOT EXISTS visibility TEXT NOT NULL DEFAULT 'private'
        CHECK (visibility IN ('private', 'public')),
    ADD COLUMN IF NOT EXISTS slug TEXT,
    ADD COLUMN IF NOT EXISTS description TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS encryption_mode TEXT NOT NULL DEFAULT 'e2e'
        CHECK (encryption_mode IN ('e2e', 'none'));

-- Unique public slug when set (empty/null allowed for private).
CREATE UNIQUE INDEX IF NOT EXISTS idx_group_settings_slug_unique
    ON group_settings (lower(slug))
    WHERE slug IS NOT NULL AND btrim(slug) <> '';

-- Existing groups stay private e2e multi-sender.
UPDATE group_settings
SET visibility = 'private',
    encryption_mode = 'e2e'
WHERE visibility IS NULL OR encryption_mode IS NULL;
