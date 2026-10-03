#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# С Mac: сборка и загрузка на VPS, затем установка по SSH.
# Использование:
#   VPS_HOST=203.0.113.10 VPS_USER=root ./scripts/push-to-vps.sh
#   PUBLIC_DOMAIN=api.example.com ./scripts/push-to-vps.sh   # optional HTTPS name
set -euo pipefail

: "${VPS_HOST:?Set VPS_HOST to the server address}"
VPS_USER="${VPS_USER:-root}"
REMOTE_DIR="${REMOTE_DIR:-/root/glagolitsa-deploy}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "==> Build linux binary"
"$ROOT/scripts/build-linux-amd64.sh"

SSH_OPTS=(-i "${SSH_KEY:-$HOME/.ssh/id_ed25519}" -o StrictHostKeyChecking=accept-new)

echo "==> Upload to $VPS_USER@$VPS_HOST:$REMOTE_DIR"
ssh "${SSH_OPTS[@]}" "$VPS_USER@$VPS_HOST" "mkdir -p '$REMOTE_DIR'"
rsync -avz -e "ssh ${SSH_OPTS[*]}" \
  "$ROOT/bin/server-linux-amd64" \
  "$ROOT/scripts/remote-install.sh" \
  "$VPS_USER@$VPS_HOST:$REMOTE_DIR/"
rsync -avz -e "ssh ${SSH_OPTS[*]}" --delete \
  "$ROOT/deploy/" \
  "$VPS_USER@$VPS_HOST:$REMOTE_DIR/deploy/"

echo "==> Remote install"
PUBLIC_DOMAIN="${PUBLIC_DOMAIN:-api.glagolit.me}"
ssh "${SSH_OPTS[@]}" "$VPS_USER@$VPS_HOST" \
  "cd '$REMOTE_DIR' && PUBLIC_HOST='$VPS_HOST' PUBLIC_DOMAIN='$PUBLIC_DOMAIN' INSTALL_ROOT=/opt/glagolitsa bash remote-install.sh"

echo ""
echo "Проверка с Mac:"
curl -sf "http://${VPS_HOST}/api/health" && echo ""
curl -sf "https://${PUBLIC_DOMAIN}/api/health" && echo "" || true
echo "Android: apiBaseUrl=https://${PUBLIC_DOMAIN}"