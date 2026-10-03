#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Сброс данных на prod VPS: очистка БД + dev seed users + перезапуск API.
# Оператора создайте в приложении, затем назначьте роль через set-account-role.sh.
#
# На сервере (root):
#   bash /root/glagolitsa-deploy/reset-prod-data.sh
set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/root/glagolitsa-deploy/deploy}"
ENV_FILE="${ENV_FILE:-/etc/glagolitsa/production.env}"
COMPOSE=(docker compose -f "$DEPLOY_DIR/docker-compose.prod.yml" --env-file "$DEPLOY_DIR/.env")

if [[ "${EUID}" -ne 0 ]]; then
  echo "Запусти от root"
  exit 1
fi

echo "==> Очистка БД (кроме schema_migrations)"
"${COMPOSE[@]}" exec -T postgres psql -U glagolitsa -d glagolitsa <<'SQL'
TRUNCATE TABLE
  sync_offline_queue, sync_snapshots, sync_devices, sync_events,
  notification_retry_queue, notification_delivery_log, notification_preferences, push_tokens,
  jobs, media_files, attachments,
  group_audit_events, group_pinned_items, group_bans, group_join_requests, group_invites, group_settings,
  call_key_offers, call_participants, calls,
  envelope_delivery, chat_events, messages_queue, messages,
  messaging_limits, user_presence, presence_privacy,
  device_key_attestations, key_change_events, prekeys, devices,
  webauthn_credentials, sessions, registration_challenges,
  audit_events, account_reputation, chat_members, chats,
  profiles, users,
  instance_identity
RESTART IDENTITY CASCADE;
SQL

echo "==> Включить dev seed users (SEED_DEV_USERS=true)"
if grep -q '^SEED_DEV_USERS=' "$ENV_FILE"; then
  sed -i 's/^SEED_DEV_USERS=.*/SEED_DEV_USERS=true/' "$ENV_FILE"
else
  echo 'SEED_DEV_USERS=true' >>"$ENV_FILE"
fi

echo "==> Перезапуск API (seed при старте)"
systemctl restart glagolitsa-api
sleep 2

echo "==> Пользователи"
"${COMPOSE[@]}" exec -T postgres psql -U glagolitsa -d glagolitsa -c \
  "SELECT p.username, u.account_status, u.account_role FROM users u JOIN profiles p ON p.user_id=u.id ORDER BY p.username;"

echo ""
echo "Готово."
echo "  Dev seed users enabled; credentials are intentionally not printed."
echo "  Оператора зарегистрируйте в приложении, затем:"
echo "    USERNAME=<username> ROLE=user bash /root/glagolitsa-deploy/set-account-role.sh"
echo ""
echo "Позже (VPN/HTTPS): PUBLIC_DOMAIN=api.ваш-домен setup-public-domain.sh"
