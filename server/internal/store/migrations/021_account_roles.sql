-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Account roles: global server-side privileges for admin/test operator accounts.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS account_role TEXT NOT NULL DEFAULT 'user';

ALTER TABLE users
    DROP CONSTRAINT IF EXISTS users_account_role_check;

ALTER TABLE users
    ADD CONSTRAINT users_account_role_check
    CHECK (account_role IN ('user', 'admin', 'super'));
