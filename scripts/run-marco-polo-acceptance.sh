#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Signal-style Marco/Polo DM acceptance.
#
# Default: fast headless desktop acceptance (CI-safe).
# Optional:
#   RUN_LOCAL_API=1  -> relay API smoke against BASE_URL (requires Marco/Polo users there)
#   RUN_UI_SMOKE=1   -> dual-device UI smoke via existing Maestro/ADB flow
#
# Examples:
#   ./scripts/run-marco-polo-acceptance.sh
#   RUN_LOCAL_API=1 BASE_URL=http://127.0.0.1:8080 ./scripts/run-marco-polo-acceptance.sh
#   RUN_UI_SMOKE=1 SENDER_ADB_SERIAL=emulator-5554 RECIPIENT_ADB_SERIAL=emulator-5556 ./scripts/run-marco-polo-acceptance.sh

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/dev-accounts.sh
source "$ROOT/scripts/lib/dev-accounts.sh"

SENDER_USERNAME="${SENDER_USERNAME:-$GLAGOLITSA_DEV_PRIMARY_USERNAME}"
SENDER_PASSWORD="${SENDER_PASSWORD:-$GLAGOLITSA_DEV_PRIMARY_PASSWORD}"
RECIPIENT_USERNAME="${RECIPIENT_USERNAME:-$GLAGOLITSA_DEV_SECONDARY_USERNAME}"
RECIPIENT_PASSWORD="${RECIPIENT_PASSWORD:-$GLAGOLITSA_DEV_SECONDARY_PASSWORD}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"

log() { printf '[marco-polo-acceptance] %s\n' "$*"; }

log "1/3 headless Marco/Polo acceptance"
(
  cd "$ROOT"
  ./gradlew :shared:desktopTest --tests com.glagolitsa.repository.MessengerRepositoryDmAcceptanceTest
)

if [[ "${RUN_LOCAL_API:-0}" == "1" ]]; then
  log "2/3 local API relay smoke: $SENDER_USERNAME -> $RECIPIENT_USERNAME @ $BASE_URL"
  SENDER_USERNAME="$SENDER_USERNAME" \
    SENDER_PASSWORD="$SENDER_PASSWORD" \
    RECIPIENT_USERNAME="$RECIPIENT_USERNAME" \
    RECIPIENT_PASSWORD="$RECIPIENT_PASSWORD" \
    BASE_URL="$BASE_URL" \
    "$ROOT/scripts/verify-public-messaging-path.sh"
else
  log "2/3 local API relay smoke skipped (set RUN_LOCAL_API=1)"
fi

if [[ "${RUN_UI_SMOKE:-0}" == "1" ]]; then
  log "3/3 dual-device UI smoke: $SENDER_USERNAME -> $RECIPIENT_USERNAME"
  SENDER_USERNAME="$SENDER_USERNAME" \
    SENDER_PASSWORD="$SENDER_PASSWORD" \
    RECIPIENT_USERNAME="$RECIPIENT_USERNAME" \
    RECIPIENT_PASSWORD="$RECIPIENT_PASSWORD" \
    BASE_URL="$BASE_URL" \
    "$ROOT/scripts/run-maestro-message-dual-e2e.sh"
else
  log "3/3 UI smoke skipped (set RUN_UI_SMOKE=1 and provide ADB serials)"
fi

log "done"
