-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Group Service: membership, roles, permissions — no message content.

ALTER TABLE chat_members
    ADD COLUMN IF NOT EXISTS role TEXT NOT NULL DEFAULT 'member'
        CHECK (role IN ('admin', 'member'));

ALTER TABLE chat_members
    ADD COLUMN IF NOT EXISTS muted_until TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS group_settings (
    group_id UUID PRIMARY KEY REFERENCES chats(id) ON DELETE CASCADE,
    membership_version INT NOT NULL DEFAULT 1,
    join_by_invite_only BOOLEAN NOT NULL DEFAULT TRUE,
    join_requests_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    perm_invite TEXT NOT NULL DEFAULT 'admin' CHECK (perm_invite IN ('admin', 'all')),
    perm_send_messages TEXT NOT NULL DEFAULT 'all' CHECK (perm_send_messages IN ('admin', 'all')),
    perm_pin TEXT NOT NULL DEFAULT 'admin' CHECK (perm_pin IN ('admin', 'all')),
    perm_moderate TEXT NOT NULL DEFAULT 'admin' CHECK (perm_moderate IN ('admin', 'all')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS group_invites (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    token TEXT NOT NULL UNIQUE,
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    max_uses INT,
    use_count INT NOT NULL DEFAULT 0,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_group_invites_group
    ON group_invites (group_id)
    WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS group_join_requests (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    resolved_by UUID REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE (group_id, user_id)
);

CREATE TABLE IF NOT EXISTS group_bans (
    group_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    banned_by UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason TEXT,
    banned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (group_id, user_id)
);

CREATE TABLE IF NOT EXISTS group_pinned_items (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    item_ref TEXT NOT NULL,
    pinned_by UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    pinned_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_group_pinned_group
    ON group_pinned_items (group_id, pinned_at DESC);

CREATE TABLE IF NOT EXISTS group_audit_events (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    actor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action TEXT NOT NULL,
    target_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_group_audit_group_created
    ON group_audit_events (group_id, created_at DESC);

-- Default settings for existing groups.
INSERT INTO group_settings (group_id)
SELECT c.id
FROM chats c
WHERE c.chat_type = 'group'
ON CONFLICT (group_id) DO NOTHING;