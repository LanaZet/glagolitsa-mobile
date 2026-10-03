-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Creative channel foundations:
--  - open post metadata (album/cover/tags as JSON)
--  - thread/comment columns on messages
--  - comment/react policies on group_settings

ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS reply_to_message_id UUID,
    ADD COLUMN IF NOT EXISTS thread_root_id UUID,
    ADD COLUMN IF NOT EXISTS thread_parent_id UUID,
    ADD COLUMN IF NOT EXISTS visibility TEXT,
    ADD COLUMN IF NOT EXISTS metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS thread_reply_count INT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_messages_chat_thread_root
    ON messages (chat_id, thread_root_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_messages_chat_roots
    ON messages (chat_id, created_at DESC)
    WHERE deleted_at IS NULL
      AND (thread_root_id IS NULL);

ALTER TABLE group_settings
    ADD COLUMN IF NOT EXISTS perm_comment TEXT NOT NULL DEFAULT 'all'
        CHECK (perm_comment IN ('admin', 'all', 'none')),
    ADD COLUMN IF NOT EXISTS perm_react TEXT NOT NULL DEFAULT 'all'
        CHECK (perm_react IN ('admin', 'all', 'none'));

-- Channels default: everyone can comment/react; groups keep all (harmless).
UPDATE group_settings gs
SET perm_comment = 'all',
    perm_react = 'all'
WHERE EXISTS (
    SELECT 1 FROM chats c WHERE c.id = gs.group_id AND c.chat_type = 'channel'
);
