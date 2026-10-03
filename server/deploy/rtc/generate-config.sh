#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Generate livekit.yaml keys + turnserver.conf from env (never commit output secrets).
# Usage:
#   export LIVEKIT_API_KEY=... LIVEKIT_API_SECRET=... TURN_SHARED_SECRET=... TURN_DOMAIN=...
#   optional: LIVEKIT_NODE_IP=x.x.x.x  EXTERNAL_IP=x.x.x.x
#   ./server/deploy/rtc/generate-config.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
KEY="${LIVEKIT_API_KEY:?set LIVEKIT_API_KEY}"
SECRET="${LIVEKIT_API_SECRET:?set LIVEKIT_API_SECRET}"
TURN_SECRET="${TURN_SHARED_SECRET:?set TURN_SHARED_SECRET}"
TURN_DOMAIN="${TURN_DOMAIN:-turn.localhost}"
REGION="${RTC_REGION_ID:-primary}"
REDIS_ADDR="${LIVEKIT_REDIS_ADDRESS:-redis-rtc:6379}"
NODE_IP="${LIVEKIT_NODE_IP:-}"
EXTERNAL_IP="${EXTERNAL_IP:-$NODE_IP}"

umask 077
OUT_LK="$ROOT/livekit.generated.yaml"
OUT_TURN="$ROOT/turnserver.generated.conf"

cat >"$OUT_LK" <<EOF
# GENERATED — do not commit. keys match LIVEKIT_API_KEY/SECRET on Go API.
port: 7880
bind_addresses:
  - ""
rtc:
  tcp_port: 7881
  port_range_start: 50000
  port_range_end: 50100
  use_ice_lite: false
EOF
if [[ -n "$NODE_IP" ]]; then
  cat >>"$OUT_LK" <<EOF
  use_external_ip: true
  node_ip: $NODE_IP
EOF
fi
cat >>"$OUT_LK" <<EOF
redis:
  address: $REDIS_ADDR
keys:
  $KEY: $SECRET
logging:
  level: info
room:
  auto_create: true
  empty_timeout: 300
  max_participants: 16
region: $REGION
EOF

TURN_CERT="${TURN_CERT:-/etc/glagolitsa/turn-certs/fullchain.pem}"
TURN_PKEY="${TURN_PKEY:-/etc/glagolitsa/turn-certs/privkey.pem}"
cat >"$OUT_TURN" <<EOF
# GENERATED — do not commit.
listening-port=3478
alt-listening-port=443
tls-listening-port=5349
fingerprint
lt-cred-mech
static-auth-secret=$TURN_SECRET
realm=$TURN_DOMAIN
min-port=49160
max-port=49200
no-multicast-peers
no-cli
no-tlsv1
no-tlsv1_1
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=192.168.0.0-192.168.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=127.0.0.1-127.255.255.255
EOF
if [[ -f "$TURN_CERT" && -f "$TURN_PKEY" ]]; then
  cat >>"$OUT_TURN" <<EOF
cert=$TURN_CERT
pkey=$TURN_PKEY
EOF
fi
if [[ -n "$EXTERNAL_IP" ]]; then
  echo "external-ip=$EXTERNAL_IP" >>"$OUT_TURN"
fi

echo "Wrote $OUT_LK"
echo "Wrote $OUT_TURN"
echo "Copy to production paths, chmod 600, do not git-add."
