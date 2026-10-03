#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Локальная очистка зомби-устройств Marco/Polo и их relay-очередей.
# Оставляет по одному самому новому active-устройству на аккаунт.
#
#   ./scripts/cleanup-local-dm-devices.sh
#   KEEP_MARCO_DEVICE=uuid KEEP_POLO_DEVICE=uuid ./scripts/cleanup-local-dm-devices.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT/../glagolitsa/server/docker-compose.yml}"
# shellcheck source=lib/dev-accounts.sh
source "$ROOT/scripts/lib/dev-accounts.sh"
USERS="${CLEANUP_USERS:-$GLAGOLITSA_DEV_SECONDARY_USERNAME,$GLAGOLITSA_DEV_PRIMARY_USERNAME}"

log() { printf '[cleanup-local-dm-devices] %s\n' "$*"; }
fail() { printf '[cleanup-local-dm-devices] FAIL: %s\n' "$*" >&2; exit 1; }

[[ -f "$COMPOSE_FILE" ]] || fail "compose not found: $COMPOSE_FILE"
docker compose -f "$COMPOSE_FILE" ps postgres 2>/dev/null | grep -q Up \
  || fail "postgres not running — start server docker compose first"

psql_exec() {
  docker compose -f "$COMPOSE_FILE" exec -T postgres \
    psql -U glagolitsa -d glagolitsa -v ON_ERROR_STOP=1 -Atqc "$1"
}

IFS=',' read -r -a USER_LIST <<< "$USERS"

for user_lc in "${USER_LIST[@]}"; do
  user_lc="$(printf '%s' "$user_lc" | tr '[:upper:]' '[:lower:]' | xargs)"
  [[ -n "$user_lc" ]] || continue

  user_upper="$(printf '%s' "$user_lc" | tr '[:lower:]' '[:upper:]')"
  keep_var="KEEP_${user_upper}_DEVICE"
  keep_device="${!keep_var:-}"

  if [[ -z "$keep_device" ]]; then
    keep_device="$(psql_exec "
      SELECT d.device_id
      FROM devices d
      JOIN profiles p ON p.user_id = d.account_id
      WHERE lower(p.username) = lower('${user_lc}')
        AND d.device_status = 'active'
      ORDER BY d.created_at DESC
      LIMIT 1;
    ")"
  fi

  [[ -n "$keep_device" ]] || { log "skip @$user_lc — no devices"; continue; }

  log "@$user_lc keep device=$keep_device"

  before_devices="$(psql_exec "
    SELECT count(*) FROM devices d
    JOIN profiles p ON p.user_id = d.account_id
    WHERE lower(p.username) = lower('${user_lc}');
  ")"
  before_queue="$(psql_exec "
    SELECT count(*) FROM messages_queue mq
    WHERE mq.mailbox_token IN (
      SELECT d.mailbox_token FROM devices d
      JOIN profiles p ON p.user_id = d.account_id
      WHERE lower(p.username) = lower('${user_lc}')
        AND d.mailbox_token IS NOT NULL
    );
  ")"

  psql_exec "
    DELETE FROM messages_queue mq
    USING devices d
    JOIN profiles p ON p.user_id = d.account_id
    WHERE lower(p.username) = lower('${user_lc}')
      AND mq.mailbox_token = d.mailbox_token
      AND d.device_id <> '${keep_device}';
  " >/dev/null

  psql_exec "
    DELETE FROM devices d
    USING profiles p
    WHERE p.user_id = d.account_id
      AND lower(p.username) = lower('${user_lc}')
      AND d.device_id <> '${keep_device}';
  " >/dev/null

  after_devices="$(psql_exec "
    SELECT count(*) FROM devices d
    JOIN profiles p ON p.user_id = d.account_id
    WHERE lower(p.username) = lower('${user_lc}');
  ")"
  after_queue="$(psql_exec "
    SELECT count(*) FROM messages_queue mq
    WHERE mq.mailbox_token IN (
      SELECT d.mailbox_token FROM devices d
      JOIN profiles p ON p.user_id = d.account_id
      WHERE lower(p.username) = lower('${user_lc}')
        AND d.mailbox_token IS NOT NULL
    );
  ")"

  removed_devices=$((before_devices - after_devices))
  removed_queue=$((before_queue - after_queue))
  log "@$user_lc removed devices=$removed_devices queue_envelopes=$removed_queue (left devices=$after_devices queue=$after_queue)"
done

log "done"
