#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Установка с веб-консоли is*hosting, когда scp с Mac не работает.
#
# 1) На Mac (VPN выключен): загрузить бинарник и получить ссылку:
#      curl --upload-file bin/server-linux-amd64 https://transfer.sh/glagolitsa-server
# 2) На сервере (root):
#      curl -fsSL -o /tmp/vps-console-bootstrap.sh \
#        'https://...'   # или скопировать этот файл вручную
#      BINARY_URL='https://transfer.sh/.../glagolitsa-server' \
#        PUBLIC_HOST=203.0.113.10 bash /tmp/vps-console-bootstrap.sh
set -euo pipefail

: "${PUBLIC_HOST:?Set PUBLIC_HOST to the public address of this machine}"
INSTALL_ROOT="${INSTALL_ROOT:-/opt/glagolitsa}"
DEPLOY_ROOT="${DEPLOY_ROOT:-/root/glagolitsa-deploy}"
BINARY_URL="${BINARY_URL:-}"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Запусти от root"
  exit 1
fi

if [[ -z "$BINARY_URL" ]]; then
  echo "Задай BINARY_URL — прямая ссылка на server-linux-amd64"
  echo "Пример (Mac, VPN выкл.): curl --upload-file bin/server-linux-amd64 https://transfer.sh/glagolitsa-server"
  exit 1
fi

mkdir -p "$DEPLOY_ROOT/deploy" "$DEPLOY_ROOT/bin"

echo "==> Скачать бинарник"
curl -fL --retry 3 -o "$DEPLOY_ROOT/server-linux-amd64" "$BINARY_URL"
chmod 0755 "$DEPLOY_ROOT/server-linux-amd64"

echo "==> deploy-файлы"
cat >"$DEPLOY_ROOT/deploy/docker-compose.prod.yml" <<'EOF'
services:
  postgres:
    image: postgres:16-alpine
    restart: unless-stopped
    environment:
      POSTGRES_USER: glagolitsa
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD in .env}
      POSTGRES_DB: glagolitsa
    ports:
      - "127.0.0.1:5432:5432"
    volumes:
      - glagolitsa_pg:/var/lib/postgresql/data
    command:
      - postgres
      - -c
      - shared_buffers=64MB
      - -c
      - effective_cache_size=128MB
      - -c
      - maintenance_work_mem=32MB
      - -c
      - work_mem=4MB
      - -c
      - max_connections=30
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U glagolitsa -d glagolitsa"]
      interval: 5s
      timeout: 3s
      retries: 10
    mem_limit: 384m

volumes:
  glagolitsa_pg:
EOF

cat >"$DEPLOY_ROOT/deploy/production.env.example" <<EOF
ENV=production
GO_ENV=production
ADDR=127.0.0.1:8080
JWT_SECRET=change-me
DEVICE_CONFIRM_SECRET=change-me-too
DATABASE_URL=postgres://glagolitsa:POSTGRES_PASSWORD@127.0.0.1:5432/glagolitsa?sslmode=disable
MINIO_ENABLED=false
BLOBSTORE_BACKEND=filesystem
BLOBSTORE_DIR=data/blobs
FRONTEND_ORIGIN=http://${PUBLIC_HOST}
SEED_DEV_USERS=false
LOG_LEVEL=info
METRICS_ENABLED=true
EOF

cat >"$DEPLOY_ROOT/deploy/glagolitsa-api.service" <<EOF
[Unit]
Description=Glagolitsa API (chat-only)
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
User=glagolitsa
Group=glagolitsa
WorkingDirectory=${INSTALL_ROOT}
EnvironmentFile=/etc/glagolitsa/production.env
ExecStart=${INSTALL_ROOT}/bin/server
Restart=on-failure
RestartSec=5
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

if [[ ! -f "$DEPLOY_ROOT/remote-install.sh" ]]; then
  echo "Нужен remote-install.sh в $DEPLOY_ROOT"
  echo "Скопируй с Mac или из репозитория glagolitsa/server/scripts/remote-install.sh"
  exit 1
fi

echo "==> Установка"
cd "$DEPLOY_ROOT"
PUBLIC_HOST="$PUBLIC_HOST" INSTALL_ROOT="$INSTALL_ROOT" bash remote-install.sh
