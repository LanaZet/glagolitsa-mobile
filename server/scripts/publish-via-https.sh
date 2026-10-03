#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Упаковать деплой и выложить по HTTPS (работает с включённым VPN; SSH не нужен).
#
# На Mac:
#   ./scripts/publish-via-https.sh
#
# Скопируйте ссылку и выполните команду для сервера (веб-консоль is*hosting).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="$(mktemp -d)"
ARCHIVE="$STAGE/glagolitsa-deploy.tar.gz"
: "${PUBLIC_HOST:?Set PUBLIC_HOST to the server address}"

cleanup() { rm -rf "$STAGE"; }
trap cleanup EXIT

echo "==> Build linux binary"
"$ROOT/scripts/build-linux-amd64.sh"

echo "==> Package"
PKG="$STAGE/glagolitsa-deploy"
mkdir -p "$PKG/deploy"
cp "$ROOT/bin/server-linux-amd64" "$PKG/"
cp "$ROOT/scripts/remote-install.sh" "$PKG/"
cp "$ROOT/deploy/docker-compose.prod.yml" "$PKG/deploy/"
cp "$ROOT/deploy/production.env.example" "$PKG/deploy/"
cp "$ROOT/deploy/glagolitsa-api.service" "$PKG/deploy/"
tar -czf "$ARCHIVE" -C "$STAGE" glagolitsa-deploy
ls -lh "$ARCHIVE"

echo "==> Upload via HTTPS (VPN можно не выключать)"
RESPONSE="$(curl -sf --retry 3 --upload-file "$ARCHIVE" "https://transfer.sh/glagolitsa-deploy-${PUBLIC_HOST}.tar.gz")"
URL="$(echo "$RESPONSE" | tail -1 | tr -d '\r')"

if [[ -z "$URL" || "$URL" != https://* ]]; then
  echo "Не удалось получить ссылку. Ответ:"
  echo "$RESPONSE"
  exit 1
fi

echo ""
echo "Ссылка на архив (≈14 дней):"
echo "  $URL"
echo ""
echo "=== Обход VPN только для VPS (Mac, VPN остаётся включённым): ==="
echo "  sudo route add -host $PUBLIC_HOST 10.214.88.200"
echo "  # если не сработает — подставьте шлюз en0: netstat -nr | grep 'default.*en0'"
echo ""
echo "=== На сервере (веб-консоль is*hosting), вставьте целиком: ==="
cat <<EOF

mkdir -p /root/glagolitsa-deploy
curl -fL --retry 3 -o /tmp/glagolitsa-deploy.tar.gz '$URL'
tar -xzf /tmp/glagolitsa-deploy.tar.gz -C /root
ls -la /root/glagolitsa-deploy/
cd /root/glagolitsa-deploy
PUBLIC_HOST=$PUBLIC_HOST INSTALL_ROOT=/opt/glagolitsa bash remote-install.sh

EOF