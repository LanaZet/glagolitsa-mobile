#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Reset account password in production Postgres (bcrypt DefaultCost, same as auth.HashPassword).
#
# From Mac (SSH to VPS):
#   USERNAME=alice PASSWORD='...' VPS_HOST=203.0.113.10 ./scripts/set-account-password.sh
#
# On VPS as root:
#   USERNAME=alice PASSWORD='...' bash set-account-password.sh
set -euo pipefail

USERNAME="${USERNAME:-}"
PASSWORD="${PASSWORD:-}"
: "${VPS_HOST:?Set VPS_HOST to the server address}"
VPS_USER="${VPS_USER:-root}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
DEPLOY_DIR="${DEPLOY_DIR:-/root/glagolitsa-deploy/deploy}"

if [[ -z "$USERNAME" || -z "$PASSWORD" ]]; then
  echo "Usage: USERNAME=alice PASSWORD='…' VPS_HOST=<public-ip> $0" >&2
  exit 1
fi
if [[ ${#PASSWORD} -lt 8 ]]; then
  echo "PASSWORD must be at least 8 characters" >&2
  exit 1
fi

hash_password() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  cat >"$tmp/main.go" <<'GO'
package main

import (
	"fmt"
	"os"

	"golang.org/x/crypto/bcrypt"
)

func main() {
	h, err := bcrypt.GenerateFromPassword([]byte(os.Getenv("PASSWORD")), bcrypt.DefaultCost)
	if err != nil {
		fmt.Fprintf(os.Stderr, "bcrypt hash failed: %v\n", err)
		os.Exit(1)
	}
	fmt.Print(string(h))
}
GO
  (
    cd "$tmp"
    go mod init hashpw >/dev/null 2>&1
    go get golang.org/x/crypto/bcrypt >/dev/null 2>&1
    PASSWORD="$PASSWORD" go run .
  )
}

echo "==> Hashing password for @$USERNAME (bcrypt)"
HASH="$(hash_password)"
if [[ ${#HASH} -lt 50 || "$HASH" != \$2* ]]; then
  echo "Failed to produce bcrypt hash" >&2
  exit 1
fi
HB64="$(printf %s "$HASH" | base64 | tr -d '\n')"

echo "==> Applying password on $VPS_HOST"
ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 \
  "$VPS_USER@$VPS_HOST" \
  "HB64='$HB64' USERNAME='$USERNAME' DEPLOY_DIR='$DEPLOY_DIR' bash -s" <<'REMOTE'
set -euo pipefail
HASH="$(printf %s "$HB64" | base64 -d)"
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q 'postgres'; then
  docker exec -i deploy-postgres-1 psql -U glagolitsa -d glagolitsa -v ON_ERROR_STOP=1 \
    -v hash="$HASH" -v username="$USERNAME" <<'SQL'
UPDATE users u
SET password_hash = :'hash'
FROM profiles p
WHERE p.user_id = u.id
  AND lower(p.username) = lower(:'username');
SELECT p.username, length(u.password_hash) AS ph_len
FROM users u
JOIN profiles p ON p.user_id = u.id
WHERE lower(p.username) = lower(:'username');
SQL
else
  COMPOSE="docker compose -f $DEPLOY_DIR/docker-compose.prod.yml --env-file $DEPLOY_DIR/.env"
  $COMPOSE exec -T postgres psql -U glagolitsa -d glagolitsa -v ON_ERROR_STOP=1 \
    -v hash="$HASH" -v username="$USERNAME" <<'SQL'
UPDATE users u
SET password_hash = :'hash'
FROM profiles p
WHERE p.user_id = u.id
  AND lower(p.username) = lower(:'username');
SELECT p.username, length(u.password_hash) AS ph_len
FROM users u
JOIN profiles p ON p.user_id = u.id
WHERE lower(p.username) = lower(:'username');
SQL
fi
REMOTE

echo "OK — password reset for @$USERNAME"
