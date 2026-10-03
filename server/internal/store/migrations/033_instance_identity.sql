-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Stable identity for this server workspace. Clients bind local sessions to this
-- value so a phone cannot silently reuse credentials from a previous backend.
CREATE TABLE IF NOT EXISTS instance_identity (
    id TEXT PRIMARY KEY CHECK (id = 'default'),
    server_id UUID NOT NULL DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO instance_identity (id)
VALUES ('default')
ON CONFLICT (id) DO NOTHING;
