#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# E2E доставки DM: send → API queue verify → receive → post API verify.
#
# Три гарантии:
#   1. Доставка и шифрование — verify-e2e-relay-encryption.sh
#   2. Читаемость — Maestro receive: assertVisible расшифрованного текста
#   3. Обновление UI — Maestro send: assertVisible сразу после отправки
#
#   ./scripts/run-maestro-message-e2e.sh
#   MAESTRO_DEVICE=emulator ./scripts/run-maestro-message-e2e.sh
#   SENDER_PASSWORD='...' RECIPIENT_PASSWORD='...' ./scripts/run-maestro-message-e2e.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
E2E_ROOT="$ROOT"
# shellcheck source=e2e-defaults.sh
source "$ROOT/scripts/e2e-defaults.sh"
# shellcheck source=e2e-dm-security.sh
source "$ROOT/scripts/e2e-dm-security.sh"
# shellcheck source=maestro-env.sh
source "$ROOT/scripts/maestro-env.sh"

RUN_ID="${RUN_ID:-$(date +%s 2>/dev/null || echo 0)}"
[[ -n "$RUN_ID" && "$RUN_ID" != "0" ]] || RUN_ID="$RANDOM$RANDOM"
SENDER_USERNAME="${SENDER_USERNAME:-$E2E_DEFAULT_SENDER_USERNAME}"
SENDER_PASSWORD="${SENDER_PASSWORD:-$E2E_DEFAULT_SENDER_PASSWORD}"
RECIPIENT_USERNAME="${RECIPIENT_USERNAME:-$E2E_DEFAULT_RECIPIENT_USERNAME}"
RECIPIENT_PASSWORD="${RECIPIENT_PASSWORD:-$E2E_DEFAULT_RECIPIENT_PASSWORD}"
E2E_MSG="${E2E_MSG:-e2e-${RUN_ID}-dm}"
BASE_URL="${BASE_URL:-$E2E_DEFAULT_BASE_URL}"

e2e_require_vars BASE_URL SENDER_USERNAME SENDER_PASSWORD RECIPIENT_USERNAME RECIPIENT_PASSWORD

_maestro_check_api
_maestro_install_apk

if [[ "${SKIP_PRIME_RECIPIENT:-}" != "1" ]]; then
  echo "==> Prime recipient device ($RECIPIENT_USERNAME)"
  _maestro_run test "$ROOT/maestro/flows/dm-message-prime-device.yaml" \
    -e "RECIPIENT_USERNAME=$RECIPIENT_USERNAME" \
    -e "RECIPIENT_PASSWORD=$RECIPIENT_PASSWORD"
else
  echo "==> SKIP_PRIME_RECIPIENT=1"
fi

MAESTRO_ENV=(
  -e "SENDER_USERNAME=$SENDER_USERNAME"
  -e "SENDER_PASSWORD=$SENDER_PASSWORD"
  -e "RECIPIENT_USERNAME=$RECIPIENT_USERNAME"
  -e "RECIPIENT_PASSWORD=$RECIPIENT_PASSWORD"
  -e "E2E_MSG=$E2E_MSG"
)

echo "==> Maestro DM send ($SENDER_USERNAME → $RECIPIENT_USERNAME, msg=$E2E_MSG)"
_maestro_run test "$ROOT/maestro/flows/dm-message-send.yaml" "${MAESTRO_ENV[@]}"

if [[ "${SKIP_RELAY_VERIFY:-}" != "1" ]]; then
  echo "==> API verify relay encryption (delivery + no plaintext leak)"
  e2e_verify_relay_encryption "$RECIPIENT_USERNAME" "$RECIPIENT_PASSWORD" "$E2E_MSG"
else
  echo "==> SKIP_RELAY_VERIFY=1"
fi

echo "==> Maestro DM receive"
_maestro_run test "$ROOT/maestro/flows/dm-message-receive.yaml" "${MAESTRO_ENV[@]}"

if [[ "${SKIP_API_VERIFY:-}" != "1" ]]; then
  echo "==> API verify DM chat"
  SENDER_USERNAME="$SENDER_USERNAME" SENDER_PASSWORD="$SENDER_PASSWORD" \
    RECIPIENT_USERNAME="$RECIPIENT_USERNAME" RECIPIENT_PASSWORD="$RECIPIENT_PASSWORD" \
    BASE_URL="$BASE_URL" \
    bash "$ROOT/scripts/verify-e2e-message.sh"
else
  echo "==> SKIP_API_VERIFY=1"
fi

echo "==> E2E message OK ($E2E_MSG)"
