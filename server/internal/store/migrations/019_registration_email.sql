-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Optional registration email on profile (Mattermost: User.Email).

ALTER TABLE profiles
    ADD COLUMN IF NOT EXISTS email TEXT NOT NULL DEFAULT '';

CREATE UNIQUE INDEX IF NOT EXISTS idx_profiles_email_unique
    ON profiles (email)
    WHERE email <> '';