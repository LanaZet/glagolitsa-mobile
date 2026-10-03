-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Accounts can get stuck with only pending devices when all formerly active
-- devices were revoked before the current client re-registered. Without a
-- shipped linking UI, the newest pending device is the recovery device.
WITH newest_pending_without_active AS (
    SELECT DISTINCT ON (pending.account_id) pending.device_id
    FROM devices pending
    WHERE pending.device_status = 'pending'
      AND NOT EXISTS (
          SELECT 1
          FROM devices active
          WHERE active.account_id = pending.account_id
            AND active.device_status = 'active'
      )
    ORDER BY pending.account_id, pending.created_at DESC
)
UPDATE devices
SET device_status = 'active',
    updated_at = NOW()
WHERE device_id IN (
    SELECT device_id FROM newest_pending_without_active
);
