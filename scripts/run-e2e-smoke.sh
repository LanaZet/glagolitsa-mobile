#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Release/nightly E2E smoke. This is intentionally outside the PR gate.
#
# Profiles:
#   release  registration + device-gate + dual-device DM delivery/read smoke
#   auth     registration + device-gate only
#   delivery dual-device DM delivery/read smoke only
#
#   ./scripts/run-e2e-smoke.sh
#   ./scripts/run-e2e-smoke.sh auth
#   ./scripts/run-e2e-smoke.sh delivery

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROFILE="${1:-${E2E_SMOKE_PROFILE:-release}}"

run_registration() {
  bash "$ROOT/scripts/run-maestro-register-e2e.sh"
}

run_device_gate() {
  bash "$ROOT/scripts/run-maestro-auth-test.sh"
}

run_delivery() {
  bash "$ROOT/scripts/run-maestro-message-dual-e2e.sh"
}

case "$PROFILE" in
  release|nightly|all)
    run_registration
    run_device_gate
    run_delivery
    ;;
  auth)
    run_registration
    run_device_gate
    ;;
  delivery|dm|message)
    run_delivery
    ;;
  -h|--help|help)
    cat <<'EOF'
Usage: ./scripts/run-e2e-smoke.sh [release|auth|delivery]

  release   registration + device-gate + dual-device DM delivery/read smoke
  auth      registration + device-gate only
  delivery  dual-device DM delivery/read smoke only

This script is for nightly/release validation on a prepared machine with
Maestro, adb, an emulator, and a USB phone. It is not part of the PR gate.
EOF
    ;;
  *)
    echo "Unknown E2E smoke profile: $PROFILE (release|auth|delivery)" >&2
    exit 2
    ;;
esac
