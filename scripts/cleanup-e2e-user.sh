#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Удалить E2E-пользователя с сервера (только старый e2e<digits> или новый test<letters>).
#
#   E2E_USERNAME=testbcdef E2E_USER_ID=uuid ./scripts/cleanup-e2e-user.sh
#   SKIP_E2E_CLEANUP=1 ./scripts/run-maestro-register-e2e.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=e2e-defaults.sh
source "$ROOT/scripts/e2e-defaults.sh"

E2E_USERNAME="${E2E_USERNAME:-${MAESTRO_USERNAME:-${USERNAME:-}}}"
E2E_USER_ID="${E2E_USER_ID:-}"
VPS_HOST="${VPS_HOST:-$E2E_DEFAULT_VPS_HOST}"
VPS_USER="${VPS_USER:-$E2E_DEFAULT_VPS_USER}"
DEPLOY_DIR="${DEPLOY_DIR:-$E2E_DEFAULT_DEPLOY_DIR}"

log() { printf '[cleanup-e2e-user] %s\n' "$*"; }
warn() { printf '[cleanup-e2e-user] WARN: %s\n' "$*" >&2; }

if [[ "${SKIP_E2E_CLEANUP:-}" == "1" ]]; then
  log "SKIP_E2E_CLEANUP=1 — пропуск"
  exit 0
fi

[[ -n "$E2E_USERNAME" ]] || { warn "нет E2E_USERNAME"; exit 0; }

if ! [[ "$E2E_USERNAME" =~ ^(e2e[0-9]+|test[a-z]+)$ ]]; then
  warn "отказ: username '$E2E_USERNAME' не матчится ^(e2e[0-9]+|test[a-z]+)\$ — не удаляем"
  exit 0
fi

_delete_sql() {
  local user_id="$1"
  local username="$2"
  cat <<SQL
DELETE FROM users u
USING profiles p
WHERE u.id = p.user_id
  AND lower(p.username) = lower('${username}')
  AND lower(p.username) ~ '^(e2e[0-9]+|test[a-z]+)\$'
$(if [[ -n "$user_id" ]]; then echo "  AND u.id = '${user_id}'::uuid"; fi)
RETURNING u.id, p.username;
SQL
}

_run_psql() {
  local sql="$1"
  psql -v ON_ERROR_STOP=1 -Atqc "$sql"
}

_cleanup_via_database_url() {
  local sql
  sql="$(_delete_sql "$E2E_USER_ID" "$E2E_USERNAME")"
  local result
  result="$(_run_psql "$sql" 2>/dev/null)" || return 1
  if [[ -n "$result" ]]; then
    log "удалён (DATABASE_URL): $result"
    return 0
  fi
  warn "пользователь не найден в БД"
  return 0
}

_cleanup_via_ssh() {
  local sql
  sql="$(_delete_sql "$E2E_USER_ID" "$E2E_USERNAME")"
  local remote
  remote=$(ssh -o ConnectTimeout=12 -o BatchMode=yes "${VPS_USER}@${VPS_HOST}" \
    "docker compose -f ${DEPLOY_DIR}/docker-compose.prod.yml --env-file ${DEPLOY_DIR}/.env exec -T postgres \
      psql -U glagolitsa -d glagolitsa -v ON_ERROR_STOP=1 -Atqc \"$(printf '%s' "$sql" | sed 's/"/\\"/g')\"" 2>/dev/null) || return 1
  if [[ -n "$remote" ]]; then
    log "удалён (VPS): $remote"
    return 0
  fi
  warn "пользователь не найден на VPS"
  return 0
}

log "удаление @$E2E_USERNAME${E2E_USER_ID:+ ($E2E_USER_ID)}..."

if [[ -n "${DATABASE_URL:-}" ]]; then
  _cleanup_via_database_url && exit 0
fi

if _cleanup_via_ssh; then
  exit 0
fi

warn "не удалось подключиться к БД (задай DATABASE_URL или SSH к VPS)"
exit 0
