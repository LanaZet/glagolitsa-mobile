#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Первичная настройка VPS под чаты (Ubuntu/Debian).
# Запуск на сервере: sudo bash bootstrap-chat-vps.sh
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/../deploy" && pwd)"
APP_USER=glagolitsa
APP_ROOT=/opt/glagolitsa
ENV_DIR=/etc/glagolitsa
ENV_FILE="$ENV_DIR/production.env"
COMPOSE_ENV="$DEPLOY_DIR/.env"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Запусти с sudo"
  exit 1
fi

echo "==> Пакеты: docker, caddy"
apt-get update -qq
apt-get install -y -qq docker.io docker-compose-v2 caddy

echo "==> Пользователь $APP_USER"
id "$APP_USER" &>/dev/null || useradd --system --home "$APP_ROOT" --shell /usr/sbin/nologin "$APP_USER"
mkdir -p "$APP_ROOT/bin" "$ENV_DIR"
chown -R "$APP_USER:$APP_USER" "$APP_ROOT"
chmod 700 "$ENV_DIR"

if [[ ! -f "$COMPOSE_ENV" ]]; then
  POSTGRES_PASSWORD="$(openssl rand -hex 16)"
  echo "POSTGRES_PASSWORD=$POSTGRES_PASSWORD" >"$COMPOSE_ENV"
  chmod 600 "$COMPOSE_ENV"
  echo "Создан $COMPOSE_ENV"
fi
# shellcheck disable=SC1090
source "$COMPOSE_ENV"

if [[ ! -f "$ENV_FILE" ]]; then
  JWT_SECRET="$(openssl rand -hex 32)"
  DEVICE_SECRET="$(openssl rand -hex 32)"
  cp "$DEPLOY_DIR/production.env.example" "$ENV_FILE"
  sed -i "s/change-me/$JWT_SECRET/" "$ENV_FILE"
  sed -i "s/change-me-too/$DEVICE_SECRET/" "$ENV_FILE"
  sed -i "s/POSTGRES_PASSWORD/$POSTGRES_PASSWORD/g" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  echo "Создан $ENV_FILE — проверь FRONTEND_ORIGIN"
fi

echo "==> Postgres (docker)"
docker compose -f "$DEPLOY_DIR/docker-compose.prod.yml" --env-file "$COMPOSE_ENV" up -d

echo "==> systemd"
cp "$DEPLOY_DIR/glagolitsa-api.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable glagolitsa-api

if [[ ! -x "$APP_ROOT/bin/server" ]]; then
  echo ""
  echo "Бинарник ещё не на месте. С Mac:"
  echo "  ./scripts/build-linux-amd64.sh"
  echo "  scp bin/server-linux-amd64 root@$(curl -s ifconfig.me 2>/dev/null || echo VPS_IP):$APP_ROOT/bin/server"
  echo "  ssh root@VPS 'chown glagolitsa:glagolitsa $APP_ROOT/bin/server && chmod +x $APP_ROOT/bin/server && systemctl start glagolitsa-api'"
else
  chown "$APP_USER:$APP_USER" "$APP_ROOT/bin/server"
  chmod +x "$APP_ROOT/bin/server"
  systemctl restart glagolitsa-api
fi

echo ""
echo "Готово. Проверка:"
echo "  curl -s http://127.0.0.1:8080/api/health"
echo "Настрой Caddy ($DEPLOY_DIR/Caddyfile.example), затем с телефона:"
echo "  ./gradlew :androidApp:installDebug -PapiBaseUrl=http://ПУБЛИЧНЫЙ_IP"