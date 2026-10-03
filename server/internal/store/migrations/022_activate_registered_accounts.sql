-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Registration now makes accounts active immediately so newly registered users
-- are searchable before device key upload finishes.

UPDATE users
SET account_status = 'active'
WHERE account_status = 'inactive';
