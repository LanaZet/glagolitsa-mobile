#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Назначить глобальную роль аккаунту в production Postgres.
#
# На сервере (root):
#   USERNAME=alice ROLE=user bash /root/glagolitsa-deploy/set-account-role.sh
#
# Роли:
#   user  — обычный аккаунт
#   admin — администратор
#   super — полный внутренний доступ
set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/root/glagolitsa-deploy/deploy}"
COMPOSE="docker compose -f $DEPLOY_DIR/docker-compose.prod.yml --env-file $DEPLOY_DIR/.env"
USERNAME="${USERNAME:-}"
ROLE="${ROLE:-}"
if [[ -z "$USERNAME" || -z "$ROLE" ]]; then
  echo "Usage: USERNAME=alice ROLE=user|admin|super $0" >&2
  exit 1
fi

if [[ "${EUID}" -ne 0 ]]; then
  echo "Запусти от root"
  exit 1
fi

case "$ROLE" in
  user|admin|super) ;;
  *)
    echo "Неверная роль: $ROLE. Допустимо: user, admin, super" >&2
    exit 1
    ;;
esac

echo "==> Назначаю роль $ROLE пользователю $USERNAME"
UPDATED="$($COMPOSE exec -T postgres psql -U glagolitsa -d glagolitsa \
  -v ON_ERROR_STOP=1 \
  -v username="$USERNAME" \
  -v role="$ROLE" \
  -At <<'SQL'
WITH updated AS (
  UPDATE users u
  SET account_role = :'role'
  FROM profiles p
  WHERE p.user_id = u.id
    AND lower(p.username) = lower(:'username')
  RETURNING 1
)
SELECT count(*) FROM updated;
SQL
)"
UPDATED="$(printf '%s' "$UPDATED" | tr -d '[:space:]')"
if [[ "$UPDATED" != "1" ]]; then
  echo "Пользователь $USERNAME не найден или обновлено строк: $UPDATED" >&2
  exit 1
fi

echo "==> Проверка"
$COMPOSE exec -T postgres psql -U glagolitsa -d glagolitsa \
  -v username="$USERNAME" \
  -c "
    SELECT p.username, u.account_status, u.account_role
    FROM users u
    JOIN profiles p ON p.user_id = u.id
    WHERE lower(p.username) = lower(:'username')
  "
