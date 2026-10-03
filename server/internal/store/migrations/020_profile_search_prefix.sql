-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Prefix search: autocomplete / lookup по username (Mattermost-style btree).
CREATE INDEX IF NOT EXISTS idx_profiles_username_lower_prefix
    ON profiles (lower(username) varchar_pattern_ops);