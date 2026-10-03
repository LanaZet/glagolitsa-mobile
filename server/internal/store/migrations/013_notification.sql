-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Notification Service: push tokens, preferences, delivery log, retry queue.

CREATE TABLE IF NOT EXISTS push_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    platform TEXT NOT NULL CHECK (platform IN ('ios', 'android', 'web')),
    token TEXT NOT NULL,
    token_status TEXT NOT NULL DEFAULT 'active' CHECK (token_status IN ('active', 'invalid', 'revoked')),
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    failure_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at TIMESTAMPTZ,
    UNIQUE (user_id, device_id, platform)
);

CREATE INDEX IF NOT EXISTS idx_push_tokens_user_active
    ON push_tokens (user_id)
    WHERE token_status = 'active' AND revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS notification_preferences (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    messages_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    calls_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    new_device_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    show_sender_name BOOLEAN NOT NULL DEFAULT FALSE,
    show_message_preview BOOLEAN NOT NULL DEFAULT FALSE,
    badge_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS notification_delivery_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    push_token_id UUID REFERENCES push_tokens(id) ON DELETE SET NULL,
    notification_type TEXT NOT NULL,
    platform TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('sent', 'failed', 'skipped', 'queued')),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notification_delivery_log_user
    ON notification_delivery_log (user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS notification_retry_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    notification_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    priority TEXT NOT NULL DEFAULT 'normal' CHECK (priority IN ('normal', 'high', 'voip')),
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_notification_retry_pending
    ON notification_retry_queue (next_attempt_at)
    WHERE completed_at IS NULL AND attempts < max_attempts;