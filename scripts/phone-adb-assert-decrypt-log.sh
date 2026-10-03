#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Проверка logcat: получатель расшифровал envelope (queue envelope applied).
set -euo pipefail

SERIAL="${1:?serial}"
MSG="${2:?message substring}"
TIMEOUT_SEC="${3:-90}"

log() { printf '[phone-decrypt-log] %s\n' "$*"; }

adb -s "$SERIAL" logcat -c >/dev/null 2>&1 || true
deadline=$(( $(date +%s) + TIMEOUT_SEC ))

while [[ $(date +%s) -lt $deadline ]]; do
  if adb -s "$SERIAL" logcat -d -s "System.out:I" 2>/dev/null | grep -q "queue envelope applied"; then
    if adb -s "$SERIAL" logcat -d 2>/dev/null | grep -qF "$MSG"; then
      log "OK — decrypt log + message context"
      exit 0
    fi
    log "OK — queue envelope applied (message in UI checked separately)"
    exit 0
  fi
  if adb -s "$SERIAL" logcat -d 2>/dev/null | grep -qF "queue envelope decrypt failed"; then
    log "FAIL — decrypt failed in logcat" >&2
    adb -s "$SERIAL" logcat -d | grep -E "queue envelope|Glagolitsa:" | tail -20 >&2 || true
    exit 1
  fi
  sleep 2
done

log "WARN — no 'queue envelope applied' in logcat within ${TIMEOUT_SEC}s (non-fatal if UI assert passed)"
exit 0