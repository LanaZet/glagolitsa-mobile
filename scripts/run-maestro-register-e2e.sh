#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# E2E: регистрация нового пользователя + API-верификация.
# По умолчанию — реальное USB-устройство (api.glagolit.me / VPS).
#
#   ./scripts/run-maestro-register-e2e.sh
#   ADB_SERIAL=<adb-serial> ./scripts/run-maestro-register-e2e.sh
#   MAESTRO_DEVICE=emulator ./scripts/run-maestro-register-e2e.sh
#   SKIP_API_VERIFY=1 ./scripts/run-maestro-register-e2e.sh
#   SKIP_E2E_CLEANUP=1 ./scripts/run-maestro-register-e2e.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=e2e-defaults.sh
source "$ROOT/scripts/e2e-defaults.sh"
# shellcheck source=maestro-env.sh
source "$ROOT/scripts/maestro-env.sh"

RUN_ID="${RUN_ID:-$(date +%s)}"
RUN_TOKEN="$(printf '%s' "$RUN_ID" | tr '[:upper:]' '[:lower:]' | tr '0123456789' 'abcdefghij' | tr -cd 'a-z')"
# macOS exports USERNAME=login — не использовать для тестового аккаунта.
E2E_USERNAME="${E2E_USERNAME:-${MAESTRO_USERNAME:-test${RUN_TOKEN:-manual}}}"
PASSWORD="${PASSWORD:-FlowTest2026!}"
VERIFY_USERNAME="${VERIFY_USERNAME:-$E2E_DEFAULT_VERIFY_USERNAME}"
VERIFY_PASSWORD="${VERIFY_PASSWORD:-$E2E_DEFAULT_VERIFY_PASSWORD}"
BASE_URL="${BASE_URL:-$E2E_DEFAULT_BASE_URL}"

e2e_require_vars BASE_URL VERIFY_USERNAME VERIFY_PASSWORD

_maestro_check_api
_maestro_install_apk

echo "==> Maestro E2E register on $MAESTRO_ADB_SERIAL (user=$E2E_USERNAME)"
_maestro_run test "$ROOT/maestro/flows/register-new-user.yaml" \
  -e "USERNAME=$E2E_USERNAME" \
  -e "PASSWORD=$PASSWORD" \
  -e "VERIFY_USERNAME=$VERIFY_USERNAME" \
  -e "VERIFY_PASSWORD=$VERIFY_PASSWORD"

if [[ "${SKIP_API_VERIFY:-}" == "1" ]]; then
  echo "==> SKIP_API_VERIFY=1 — API checks skipped"
  exit 0
fi

echo "==> API verify"
E2E_USERNAME="$E2E_USERNAME" PASSWORD="$PASSWORD" \
  VERIFY_USERNAME="$VERIFY_USERNAME" VERIFY_PASSWORD="$VERIFY_PASSWORD" \
  BASE_URL="$BASE_URL" \
  bash "$ROOT/scripts/verify-e2e-registration.sh"

echo "==> E2E register OK ($E2E_USERNAME)"
