#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Maestro UI smoke на реальном устройстве (по умолчанию первый USB-телефон).
# Доставка/статусы проверяются программно; dual-device Maestro — только release/nightly smoke.
#
#   ./scripts/run-maestro-e2e.sh
#   ./scripts/run-maestro-e2e.sh register          # только регистрация
#   ./scripts/run-maestro-e2e.sh device-gate       # только device gate
#   ./scripts/run-e2e-smoke.sh delivery            # release/nightly dual-device delivery smoke
#   ADB_SERIAL=emulator-5554 ./scripts/run-maestro-e2e.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${1:-ui-smoke}"

run_register() {
  bash "$ROOT/scripts/run-maestro-register-e2e.sh"
}

run_device_gate() {
  bash "$ROOT/scripts/run-maestro-auth-test.sh"
}

run_message() {
  bash "$ROOT/scripts/run-maestro-message-e2e.sh"
}

run_message_dual() {
  bash "$ROOT/scripts/run-maestro-message-dual-e2e.sh"
}

case "$TARGET" in
  ui-smoke|all)
    run_register
    run_device_gate
    ;;
  register|registration)
    run_register
    ;;
  device-gate|login|auth)
    run_device_gate
    ;;
  message|dm|messaging)
    run_message
    ;;
  message-dual|dm-dual|dual)
    run_message_dual
    ;;
  -h|--help|help)
    cat <<'EOF'
Usage: ./scripts/run-maestro-e2e.sh [ui-smoke|register|device-gate|message|message-dual]

  ui-smoke       — register-new-user + login-device-gate
  register       — новый пользователь: active, device, поиск
  device-gate    — вход без device не пускает в «Чаты»
  message        — DM UI smoke: ввод, отправка, открытие/читаемость
  message-dual   — release/nightly smoke на двух устройствах

Корректность доставки/статусов проверяется программными тестами shared.
Release/nightly smoke запускается через ./scripts/run-e2e-smoke.sh.
Телефон по умолчанию; эмулятор: MAESTRO_DEVICE=emulator ./scripts/run-maestro-e2e.sh
EOF
    ;;
  *)
    echo "Неизвестный target: $TARGET (ui-smoke|register|device-gate|message|message-dual)" >&2
    exit 2
    ;;
esac
