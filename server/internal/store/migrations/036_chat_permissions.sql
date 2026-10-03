-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Telegram-like chat/channel rights + richer invite links.

ALTER TABLE chat_members DROP CONSTRAINT IF EXISTS chat_members_role_check;
ALTER TABLE chat_members
    ADD CONSTRAINT chat_members_role_check
        CHECK (role IN ('owner', 'admin', 'member'));

ALTER TABLE chat_members
    ADD COLUMN IF NOT EXISTS admin_rights JSONB;

-- Earliest admin of each group/channel becomes owner (creator).
UPDATE chat_members cm
SET role = 'owner'
WHERE cm.role = 'admin'
  AND cm.joined_at = (
      SELECT MIN(cm2.joined_at)
      FROM chat_members cm2
      WHERE cm2.chat_id = cm.chat_id
        AND cm2.role IN ('admin', 'owner')
  );

ALTER TABLE group_settings
    ADD COLUMN IF NOT EXISTS perm_change_info TEXT NOT NULL DEFAULT 'admin';

ALTER TABLE group_settings DROP CONSTRAINT IF EXISTS group_settings_perm_change_info_check;
ALTER TABLE group_settings
    ADD CONSTRAINT group_settings_perm_change_info_check
        CHECK (perm_change_info IN ('admin', 'all'));

ALTER TABLE group_invites
    ADD COLUMN IF NOT EXISTS title TEXT NOT NULL DEFAULT '';
ALTER TABLE group_invites
    ADD COLUMN IF NOT EXISTS requires_approval BOOLEAN NOT NULL DEFAULT FALSE;
