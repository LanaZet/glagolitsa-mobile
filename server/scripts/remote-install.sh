#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Установка Glagolitsa API + Postgres + Caddy на VPS (чаты + регистрация).
# Запуск на сервере от root:
#   PUBLIC_HOST=203.0.113.10 bash remote-install.sh
set -euo pipefail

: "${PUBLIC_HOST:?Set PUBLIC_HOST to the public address of this machine}"
INSTALL_ROOT="${INSTALL_ROOT:-/opt/glagolitsa}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [[ -d "$SCRIPT_DIR/deploy" ]]; then
  REPO_ROOT="$SCRIPT_DIR"
else
  REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
fi
DEPLOY_DIR="$REPO_ROOT/deploy"
SERVER_BIN="$REPO_ROOT/server-linux-amd64"
if [[ ! -f "$SERVER_BIN" && -f "$REPO_ROOT/bin/server-linux-amd64" ]]; then
  SERVER_BIN="$REPO_ROOT/bin/server-linux-amd64"
fi
APP_USER=glagolitsa
ENV_DIR=/etc/glagolitsa
ENV_FILE="$ENV_DIR/production.env"
COMPOSE_ENV="$DEPLOY_DIR/.env"
PUBLIC_URL="http://${PUBLIC_HOST}"
DOWNLOAD_DIR="${REMOTE_DOWNLOAD_DIR:-/var/www/glagolitsa-downloads}"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Запусти от root: sudo bash $0"
  exit 1
fi

echo "==> Пакеты"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq docker.io docker-compose-v2 ufw curl ca-certificates gnupg
if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
    | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
    >/etc/apt/sources.list.d/caddy-stable.list
  apt-get update -qq
  apt-get install -y -qq caddy
fi

echo "==> Firewall"
ufw --force reset
ufw default deny incoming
ufw default allow outgoing
ufw limit 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

echo "==> TCP tuning (VPN / MTU)"
cat >/etc/sysctl.d/99-vpn-friendly.conf <<'EOF'
net.ipv4.tcp_mtu_probing = 1
net.ipv4.tcp_timestamps = 1
EOF
sysctl --system >/dev/null

echo "==> Пользователь $APP_USER"
id "$APP_USER" &>/dev/null || useradd --system --home "$INSTALL_ROOT" --shell /usr/sbin/nologin "$APP_USER"
mkdir -p "$INSTALL_ROOT/bin" "$ENV_DIR"
chown -R "$APP_USER:$APP_USER" "$INSTALL_ROOT"
chmod 700 "$ENV_DIR"

if [[ ! -f "$COMPOSE_ENV" ]]; then
  POSTGRES_PASSWORD="$(openssl rand -hex 16)"
  echo "POSTGRES_PASSWORD=$POSTGRES_PASSWORD" >"$COMPOSE_ENV"
  chmod 600 "$COMPOSE_ENV"
fi
# shellcheck disable=SC1090
source "$COMPOSE_ENV"

if [[ ! -f "$ENV_FILE" ]]; then
  JWT_SECRET="$(openssl rand -hex 32)"
  DEVICE_SECRET="$(openssl rand -hex 32)"
  cp "$DEPLOY_DIR/production.env.example" "$ENV_FILE"
  sed -i "s/change-me/$JWT_SECRET/" "$ENV_FILE"
  sed -i "s/change-me-too/$DEVICE_SECRET/" "$ENV_FILE"
  sed -i "s|POSTGRES_PASSWORD|$POSTGRES_PASSWORD|g" "$ENV_FILE"
  if grep -q '^FRONTEND_ORIGIN=' "$ENV_FILE"; then
    sed -i "s|^FRONTEND_ORIGIN=.*|FRONTEND_ORIGIN=$PUBLIC_URL|" "$ENV_FILE"
  else
    printf 'FRONTEND_ORIGIN=%s\n' "$PUBLIC_URL" >>"$ENV_FILE"
  fi
  chmod 600 "$ENV_FILE"
fi
if grep -q '^MINIO_ENABLED=' "$ENV_FILE"; then
  sed -i 's/^MINIO_ENABLED=.*/MINIO_ENABLED=false/' "$ENV_FILE"
else
  printf '\nMINIO_ENABLED=false\n' >>"$ENV_FILE"
fi
if grep -q '^BLOBSTORE_BACKEND=' "$ENV_FILE"; then
  sed -i 's/^BLOBSTORE_BACKEND=.*/BLOBSTORE_BACKEND=filesystem/' "$ENV_FILE"
else
  printf 'BLOBSTORE_BACKEND=filesystem\n' >>"$ENV_FILE"
fi
if ! grep -q '^BLOBSTORE_DIR=' "$ENV_FILE"; then
  printf 'BLOBSTORE_DIR=data/blobs\n' >>"$ENV_FILE"
fi
if ! grep -q '^WEBAUTHN_RP_ID=' "$ENV_FILE"; then
  printf '\n# Passkeys / WebAuthn (I-5)\nWEBAUTHN_RP_ID=glagolit.me\n' >>"$ENV_FILE"
fi
if ! grep -q '^WEBAUTHN_RP_DISPLAY_NAME=' "$ENV_FILE"; then
  printf 'WEBAUTHN_RP_DISPLAY_NAME=Glagolitsa\n' >>"$ENV_FILE"
fi
if ! grep -q '^WEBAUTHN_ORIGINS=' "$ENV_FILE"; then
  printf 'WEBAUTHN_ORIGINS=https://api.glagolit.me,https://glagolit.me,http://%s\n' "$PUBLIC_HOST" >>"$ENV_FILE"
fi
chown "$APP_USER:$APP_USER" "$ENV_FILE"
chmod 600 "$ENV_FILE"

echo "==> Postgres"
docker compose -f "$DEPLOY_DIR/docker-compose.prod.yml" --env-file "$COMPOSE_ENV" up -d
for _ in $(seq 1 30); do
  if docker compose -f "$DEPLOY_DIR/docker-compose.prod.yml" --env-file "$COMPOSE_ENV" exec -T postgres \
    pg_isready -U glagolitsa -d glagolitsa >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

if [[ -f "$SERVER_BIN" ]]; then
  install -m 0755 -o "$APP_USER" -g "$APP_USER" "$SERVER_BIN" "$INSTALL_ROOT/bin/server"
elif [[ -f "$INSTALL_ROOT/bin/server" ]]; then
  chown "$APP_USER:$APP_USER" "$INSTALL_ROOT/bin/server"
  chmod 0755 "$INSTALL_ROOT/bin/server"
else
  echo "Ошибка: нет бинарника server-linux-amd64 в $REPO_ROOT"
  exit 1
fi

echo "==> systemd"
cp "$DEPLOY_DIR/glagolitsa-api.service" /etc/systemd/system/
sed -i "s|/opt/glagolitsa|$INSTALL_ROOT|g" /etc/systemd/system/glagolitsa-api.service
systemctl daemon-reload
systemctl enable glagolitsa-api
systemctl restart glagolitsa-api

echo "==> Caddy reverse proxy"
mkdir -p "$DOWNLOAD_DIR"
chmod 0755 "$DOWNLOAD_DIR"
# Preserve domain HTTPS config when PUBLIC_DOMAIN is set or already configured.
PUBLIC_DOMAIN="${PUBLIC_DOMAIN:-}"
if [[ -z "$PUBLIC_DOMAIN" && -f /etc/caddy/Caddyfile ]] && grep -qE '^[a-zA-Z0-9.-]+\s*\{' /etc/caddy/Caddyfile; then
  PUBLIC_DOMAIN="$(awk '/^[a-zA-Z0-9.-]+[[:space:]]*\{/{print $1; exit}' /etc/caddy/Caddyfile || true)"
fi
if [[ -n "$PUBLIC_DOMAIN" && "$PUBLIC_DOMAIN" != ":80" ]]; then
  cat >/etc/caddy/Caddyfile <<EOF
${PUBLIC_DOMAIN} {
    encode zstd gzip
    handle_path /downloads/* {
        root * ${DOWNLOAD_DIR}
        file_server
    }
    @metrics path /metrics
    respond @metrics 404
    reverse_proxy 127.0.0.1:8080
}

http://${PUBLIC_HOST} {
    encode zstd gzip
    handle_path /downloads/* {
        root * ${DOWNLOAD_DIR}
        file_server
    }
    @metrics path /metrics
    respond @metrics 404
    reverse_proxy 127.0.0.1:8080
}
EOF
else
  cat >/etc/caddy/Caddyfile <<EOF
:80 {
    encode zstd gzip
    handle_path /downloads/* {
        root * ${DOWNLOAD_DIR}
        file_server
    }
    @metrics path /metrics
    respond @metrics 404
    reverse_proxy 127.0.0.1:8080
}
EOF
fi
systemctl enable caddy
systemctl restart caddy

sleep 2
echo ""
echo "==> Health"
curl -sf "http://127.0.0.1:8080/api/health" && echo ""
curl -sf "http://${PUBLIC_HOST}/api/health" && echo ""
echo ""
echo "Готово: $PUBLIC_URL/api/health"
echo "Регистрация: POST $PUBLIC_URL/api/auth/register (PoW включён)"
