#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Тест переписки локальных dev-аккаунтов: relay API smoke + инструкция для эмулятора.
#
#   ./scripts/test-dm.sh              # API-тест (нужен запущенный сервер)
#   ./scripts/test-dm.sh --manual     # только инструкция для ручной проверки в приложении

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"

# shellcheck source=e2e-defaults.sh
source "$ROOT_DIR/scripts/e2e-defaults.sh"
e2e_disable_proxy_for_local_api "$BASE_URL"

print_manual_steps() {
  cat <<'EOF'

── Ручная проверка DM (удобный способ: два устройства) ──

1. Подключи телефон по USB (отладка) и/или запусти эмулятор

2. Запусти:
     ./scripts/run-dual-device.sh

3. На эмуляторе: «Войти как $GLAGOLITSA_DEV_PRIMARY_LABEL»
   На телефоне:  «Войти как $GLAGOLITSA_DEV_SECONDARY_LABEL»
   (кнопки на экране входа — без logout/login)

4. На $GLAGOLITSA_DEV_PRIMARY_LABEL: Чаты → «Найти пользователя (DM)» → $GLAGOLITSA_DEV_SECONDARY_USERNAME → отправь сообщение

5. На $GLAGOLITSA_DEV_SECONDARY_LABEL: открой чат с $GLAGOLITSA_DEV_PRIMARY_USERNAME → проверь и ответь

Телефон только по Wi‑Fi:
     ./scripts/run-dual-device.sh --wifi

Аккаунты из seed:
  $(glagolitsa_dev_account_summary)

EOF
}

if [[ "${1:-}" == "--manual" ]]; then
  print_manual_steps
  exit 0
fi

echo "── Relay API smoke: $GLAGOLITSA_DEV_PRIMARY_USERNAME -> $GLAGOLITSA_DEV_SECONDARY_USERNAME ──"
BASE_URL="$BASE_URL" \
SENDER_USERNAME="${SENDER_USERNAME:-$GLAGOLITSA_DEV_PRIMARY_USERNAME}" \
SENDER_PASSWORD="${SENDER_PASSWORD:-$GLAGOLITSA_DEV_PRIMARY_PASSWORD}" \
RECIPIENT_USERNAME="${RECIPIENT_USERNAME:-$GLAGOLITSA_DEV_SECONDARY_USERNAME}" \
RECIPIENT_PASSWORD="${RECIPIENT_PASSWORD:-$GLAGOLITSA_DEV_SECONDARY_PASSWORD}" \
"$ROOT_DIR/scripts/verify-public-messaging-path.sh"
print_manual_steps
