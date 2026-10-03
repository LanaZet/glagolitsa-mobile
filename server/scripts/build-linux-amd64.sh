#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/bin/server-linux-amd64"

echo "Building $OUT ..."
(cd "$ROOT" && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o "$OUT" ./cmd/server)
ls -lh "$OUT"
echo "Upload: scp $OUT root@YOUR_VPS:/opt/glagolitsa/bin/server"