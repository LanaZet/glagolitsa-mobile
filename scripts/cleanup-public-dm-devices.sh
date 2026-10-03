#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Purge zombie active devices + undeliverable mailboxes on public (or any) API DB.
# Keeps the newest active device per account (or KEEP_<USER>_DEVICE override).
#
#   ./scripts/cleanup-public-dm-devices.sh
#   CLEANUP_USERS=Marco,Polo ./scripts/cleanup-public-dm-devices.sh
#   KEEP_MARCO_DEVICE=uuid KEEP_POLO_DEVICE=uuid ./scripts/cleanup-public-dm-devices.sh
#
# Requires SSH BatchMode to VPS (E2E_DEFAULT_VPS_* from e2e-defaults.sh).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=e2e-defaults.sh
source "$ROOT/scripts/e2e-defaults.sh"

VPS_HOST="${VPS_HOST:-$E2E_DEFAULT_VPS_HOST}"
VPS_USER="${VPS_USER:-$E2E_DEFAULT_VPS_USER}"
DEPLOY_DIR="${DEPLOY_DIR:-$E2E_DEFAULT_DEPLOY_DIR}"
USERS="${CLEANUP_USERS:-Marco,Polo}"

log() { printf '[cleanup-public-dm-devices] %s\n' "$*"; }
fail() { printf '[cleanup-public-dm-devices] FAIL: %s\n' "$*" >&2; exit 1; }

psql_remote() {
  local sql="$1"
  ssh -o ConnectTimeout=25 -o BatchMode=yes "${VPS_USER}@${VPS_HOST}" \
    "docker compose -f ${DEPLOY_DIR}/docker-compose.prod.yml --env-file ${DEPLOY_DIR}/.env exec -T postgres \
      psql -U glagolitsa -d glagolitsa -v ON_ERROR_STOP=1 -Atqc $(printf '%q' "$sql")"
}

IFS=',' read -r -a USER_LIST <<< "$USERS"

for user_lc in "${USER_LIST[@]}"; do
  user_lc="$(printf '%s' "$user_lc" | tr '[:upper:]' '[:lower:]' | xargs)"
  [[ -n "$user_lc" ]] || continue

  user_upper="$(printf '%s' "$user_lc" | tr '[:lower:]' '[:upper:]' | tr -cd 'A-Z0-9_')"
  keep_var="KEEP_${user_upper}_DEVICE"
  keep_device="${!keep_var:-}"

  if [[ -z "$keep_device" ]]; then
    keep_device="$(psql_remote "
      SELECT d.device_id
      FROM devices d
      JOIN profiles p ON p.user_id = d.account_id
      WHERE lower(p.username) = lower('${user_lc}')
        AND d.device_status = 'active'
      ORDER BY d.last_seen_at DESC NULLS LAST, d.created_at DESC
      LIMIT 1;
    ")"
  fi

  [[ -n "$keep_device" ]] || { log "skip @$user_lc — no devices"; continue; }
  log "@$user_lc keep device=$keep_device"

  before_devices="$(psql_remote "
    SELECT count(*) FROM devices d
    JOIN profiles p ON p.user_id = d.account_id
    WHERE lower(p.username) = lower('${user_lc}');
  ")"
  before_queue="$(psql_remote "
    SELECT count(*) FROM messages_queue mq
    WHERE mq.mailbox_token IN (
      SELECT d.mailbox_token FROM devices d
      JOIN profiles p ON p.user_id = d.account_id
      WHERE lower(p.username) = lower('${user_lc}')
        AND d.mailbox_token IS NOT NULL
    );
  ")"

  # Drop undeliverable ciphertext for non-kept devices first.
  psql_remote "
    DELETE FROM messages_queue mq
    USING devices d
    JOIN profiles p ON p.user_id = d.account_id
    WHERE lower(p.username) = lower('${user_lc}')
      AND mq.mailbox_token = d.mailbox_token
      AND d.device_id <> '${keep_device}';
  " >/dev/null

  # Also clear poison on the kept mailbox: PREKEY ciphertext bound to dead OTPs
  # cannot be recovered; sender must re-send after re-enroll.
  psql_remote "
    DELETE FROM messages_queue mq
    USING devices d
    JOIN profiles p ON p.user_id = d.account_id
    WHERE lower(p.username) = lower('${user_lc}')
      AND d.device_id = '${keep_device}'
      AND mq.mailbox_token = d.mailbox_token;
  " >/dev/null

  # Revoke (not hard-delete) siblings so audit/history remains but fan-out stops.
  psql_remote "
    UPDATE devices d
    SET device_status = 'revoked', updated_at = NOW()
    FROM profiles p
    WHERE p.user_id = d.account_id
      AND lower(p.username) = lower('${user_lc}')
      AND d.device_id <> '${keep_device}'
      AND d.device_status <> 'revoked';
  " >/dev/null

  # Drop server OTPs for the kept device so the next client re-register ships a
  # clean matching batch (client will re-upload on ensureDeviceRegistered).
  psql_remote "
    DELETE FROM prekeys WHERE device_id = '${keep_device}';
  " >/dev/null

  after_active="$(psql_remote "
    SELECT count(*) FROM devices d
    JOIN profiles p ON p.user_id = d.account_id
    WHERE lower(p.username) = lower('${user_lc}')
      AND d.device_status = 'active';
  ")"
  after_queue="$(psql_remote "
    SELECT count(*) FROM messages_queue mq
    WHERE mq.mailbox_token IN (
      SELECT d.mailbox_token FROM devices d
      JOIN profiles p ON p.user_id = d.account_id
      WHERE lower(p.username) = lower('${user_lc}')
        AND d.mailbox_token IS NOT NULL
        AND d.device_status = 'active'
    );
  ")"

  log "@$user_lc devices_before=$before_devices queue_before=$before_queue active_after=$after_active queue_after=$after_queue"
done

log "done — force app foreground/relogin so each kept device re-uploads OTPs, then resend DM"
