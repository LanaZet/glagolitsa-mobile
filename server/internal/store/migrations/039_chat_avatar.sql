-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Conversation icon for groups and channels (data:image URI, same as profile avatars).
ALTER TABLE chats
    ADD COLUMN IF NOT EXISTS avatar_url TEXT NOT NULL DEFAULT '';
