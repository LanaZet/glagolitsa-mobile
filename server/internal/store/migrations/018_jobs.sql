-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Mattermost-style background job queue (channels/jobs).

CREATE TABLE IF NOT EXISTS jobs (
    id TEXT PRIMARY KEY,
    type TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'in_progress', 'success', 'error', 'canceled')),
    progress BIGINT NOT NULL DEFAULT 0,
    data JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_error TEXT,
    create_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    start_at TIMESTAMPTZ,
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_jobs_status_activity
    ON jobs (status, last_activity_at ASC)
    WHERE status IN ('pending', 'in_progress');

CREATE INDEX IF NOT EXISTS idx_jobs_type_create
    ON jobs (type, create_at DESC);