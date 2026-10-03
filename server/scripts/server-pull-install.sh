#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Скачать архив деплоя по HTTPS и установить (для веб-консоли, без SSH с Mac).
#
#   BUNDLE_URL='https://transfer.sh/.../glagolitsa-deploy.tar.gz' \\
#     PUBLIC_HOST=203.0.113.10 bash server-pull-install.sh
set -euo pipefail

BUNDLE_URL="${BUNDLE_URL:-}"
: "${PUBLIC_HOST:?Set PUBLIC_HOST to the public address of this machine}"
INSTALL_ROOT="${INSTALL_ROOT:-/opt/glagolitsa}"
DEPLOY_ROOT="${DEPLOY_ROOT:-/root/glagolitsa-deploy}"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Запусти от root"
  exit 1
fi

if [[ -z "$BUNDLE_URL" ]]; then
  echo "Задай BUNDLE_URL — ссылку на glagolitsa-deploy.tar.gz с Mac (publish-via-https.sh)"
  exit 1
fi

mkdir -p "$DEPLOY_ROOT"
echo "==> Download bundle"
curl -fL --retry 3 -o /tmp/glagolitsa-deploy.tar.gz "$BUNDLE_URL"
tar -xzf /tmp/glagolitsa-deploy.tar.gz -C /root
if [[ -d /root/glagolitsa-deploy/glagolitsa-deploy ]]; then
  cp -a /root/glagolitsa-deploy/glagolitsa-deploy/. "$DEPLOY_ROOT/"
  rm -rf /root/glagolitsa-deploy/glagolitsa-deploy
fi

echo "==> Files"
ls -la "$DEPLOY_ROOT"
ls -la "$DEPLOY_ROOT/deploy"

cd "$DEPLOY_ROOT"
PUBLIC_HOST="$PUBLIC_HOST" INSTALL_ROOT="$INSTALL_ROOT" bash remote-install.sh