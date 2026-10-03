-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Group-ready call model: call + participants + invites.
-- 1:1 remains calls + two participants; callee_id is legacy DM denormalized field.

ALTER TABLE calls
    ADD COLUMN IF NOT EXISTS chat_id UUID REFERENCES chats(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS started_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS call_scope TEXT NOT NULL DEFAULT 'dm',
    ADD COLUMN IF NOT EXISTS selected_region TEXT NOT NULL DEFAULT 'primary',
    ADD COLUMN IF NOT EXISTS route_class TEXT NOT NULL DEFAULT 'single_region',
    ADD COLUMN IF NOT EXISTS policy_version INT NOT NULL DEFAULT 1;

-- Backfill starter from legacy caller.
UPDATE calls
SET started_by_user_id = caller_id
WHERE started_by_user_id IS NULL;

-- Allow group/ad-hoc rows without a single callee.
ALTER TABLE calls
    ALTER COLUMN callee_id DROP NOT NULL;

ALTER TABLE call_participants
    ADD COLUMN IF NOT EXISTS role TEXT NOT NULL DEFAULT 'joined',
    ADD COLUMN IF NOT EXISTS invite_state TEXT NOT NULL DEFAULT 'joined',
    ADD COLUMN IF NOT EXISTS media_state TEXT NOT NULL DEFAULT 'audio_only';

-- Seed participants for legacy 1:1 rows that only had caller/callee columns.
INSERT INTO call_participants (call_id, user_id, device_id, role, invite_state, media_state, joined_at)
SELECT c.id, c.caller_id, COALESCE(c.caller_device_id, ''), 'starter', 'joined', 'audio_only', c.created_at
FROM calls c
WHERE NOT EXISTS (
    SELECT 1 FROM call_participants p
    WHERE p.call_id = c.id AND p.user_id = c.caller_id
)
ON CONFLICT (call_id, user_id, device_id) DO NOTHING;

INSERT INTO call_participants (call_id, user_id, device_id, role, invite_state, media_state, joined_at)
SELECT c.id, c.callee_id, '', 'invited',
       CASE
           WHEN c.status IN ('active', 'connecting', 'ended') THEN 'joined'
           WHEN c.status = 'rejected' THEN 'rejected'
           WHEN c.status = 'missed' THEN 'missed'
           ELSE 'ringing'
       END,
       'audio_only',
       c.accepted_at
FROM calls c
WHERE c.callee_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM call_participants p
    WHERE p.call_id = c.id AND p.user_id = c.callee_id
)
ON CONFLICT (call_id, user_id, device_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS call_invites (
    id UUID PRIMARY KEY,
    call_id UUID NOT NULL REFERENCES calls(id) ON DELETE CASCADE,
    invited_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    invited_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    state TEXT NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    UNIQUE (call_id, invited_user_id)
);

CREATE INDEX IF NOT EXISTS idx_call_participants_user_call
    ON call_participants (user_id, call_id);

CREATE INDEX IF NOT EXISTS idx_call_participants_call_invite_state
    ON call_participants (call_id, invite_state);

CREATE INDEX IF NOT EXISTS idx_call_invites_invited_state_created
    ON call_invites (invited_user_id, state, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_calls_chat_created
    ON calls (chat_id, created_at DESC)
    WHERE chat_id IS NOT NULL;

-- Keep legacy history indexes from 011_calling.sql until clients migrate fully.
