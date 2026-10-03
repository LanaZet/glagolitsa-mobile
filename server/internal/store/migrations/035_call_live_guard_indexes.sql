-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Fast guard for "one live call per account" checks.
CREATE INDEX IF NOT EXISTS idx_calls_live_caller
    ON calls (caller_id, created_at DESC)
    WHERE status IN ('ringing', 'connecting', 'active');

CREATE INDEX IF NOT EXISTS idx_calls_live_callee
    ON calls (callee_id, created_at DESC)
    WHERE callee_id IS NOT NULL
      AND status IN ('ringing', 'connecting', 'active');

CREATE INDEX IF NOT EXISTS idx_call_participants_live_user
    ON call_participants (user_id, call_id)
    WHERE left_at IS NULL;
