-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Presence Service: privacy settings (ephemeral state lives in Redis).

CREATE TABLE IF NOT EXISTS presence_privacy (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    online_visibility TEXT NOT NULL DEFAULT 'contacts',
    last_seen_visibility TEXT NOT NULL DEFAULT 'contacts',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);