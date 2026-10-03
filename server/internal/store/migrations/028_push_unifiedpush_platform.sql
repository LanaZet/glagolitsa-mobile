-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- UnifiedPush / simple-push endpoints (Matrix/Telegram-style de-Google path).
-- Token value is the distributor HTTPS endpoint URL (e.g. ntfy topic).

ALTER TABLE push_tokens DROP CONSTRAINT IF EXISTS push_tokens_platform_check;
ALTER TABLE push_tokens
    ADD CONSTRAINT push_tokens_platform_check
    CHECK (platform IN ('ios', 'android', 'web', 'unifiedpush'));
