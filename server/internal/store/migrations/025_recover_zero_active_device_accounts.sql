-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Re-apply orphan-pending promotion for accounts that re-entered the
-- zero-active deadlock after 023 (client ghost-revoke while still pending).
-- Idempotent: only accounts with pending and no active devices are touched.
-- Runtime invariant is also enforced by RevokeDevice → promoteOldestPendingIfNoActive
-- and RegisterDevice promote when activeCount == 0.
WITH oldest_pending_without_active AS (
    SELECT DISTINCT ON (pending.account_id) pending.device_id
    FROM devices pending
    WHERE pending.device_status = 'pending'
      AND NOT EXISTS (
          SELECT 1
          FROM devices active
          WHERE active.account_id = pending.account_id
            AND active.device_status = 'active'
      )
    ORDER BY pending.account_id, pending.created_at ASC NULLS LAST, pending.device_id ASC
)
UPDATE devices
SET device_status = 'active',
    updated_at = NOW()
WHERE device_id IN (
    SELECT device_id FROM oldest_pending_without_active
);
