#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# HTTPS + домен. Публичный api.* лучше держать за Cloudflare orange-cloud;
# origin Caddy остаётся на VPS. TURN/UDP+TLS 443: setup-turn-443.sh.
#
# На сервере:
#   PUBLIC_DOMAIN=api.example.com bash setup-public-domain.sh
set -euo pipefail

PUBLIC_DOMAIN="${PUBLIC_DOMAIN:-api.glagolit.me}"
: "${PUBLIC_HOST:?Set PUBLIC_HOST to the public address of this machine}"
ENV_FILE="${ENV_FILE:-/etc/glagolitsa/production.env}"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Запусти от root"
  exit 1
fi

if [[ -z "$PUBLIC_DOMAIN" ]]; then
  echo "Задай PUBLIC_DOMAIN=api.glagolit.me"
  exit 1
fi

PUBLIC_URL="https://${PUBLIC_DOMAIN}"
DOWNLOAD_DIR="${REMOTE_DOWNLOAD_DIR:-/var/www/glagolitsa-downloads}"

echo "==> Caddy HTTPS для $PUBLIC_DOMAIN (DNS A must point to $PUBLIC_HOST)"
mkdir -p "$DOWNLOAD_DIR"
chmod 0755 "$DOWNLOAD_DIR"
cat >/etc/caddy/Caddyfile <<EOF
{
    servers {
        protocols h1
    }
}
${PUBLIC_DOMAIN} {
    handle_path /downloads/* {
        root * ${DOWNLOAD_DIR}
        file_server
    }
    @metrics path /metrics
    respond @metrics 404

    # LiveKit signaling (Android SDK connects to LIVEKIT_URL + /rtc).
    # Must win over the catch-all Go API proxy or clients get "404 page not found".
    handle /rtc* {
        reverse_proxy 127.0.0.1:7880 {
            flush_interval -1
            transport http {
                versions 1.1
            }
        }
    }

    # Compress static downloads only — never gzip API JSON (half-open H2/write hangs).
    @static path /downloads/*
    encode @static zstd gzip

    @apiCalls path /api/calls /api/calls/*
    @apiWs path /api/ws
    log @apiCalls {
        output stdout
        format console
    }
    log @apiWs {
        output stdout
        format console
    }

    @websocket {
        path /api/ws
        header Connection *Upgrade*
        header Upgrade websocket
    }
    reverse_proxy @websocket 127.0.0.1:8080 {
        flush_interval -1
        transport http {
            versions 1.1
        }
    }
    reverse_proxy 127.0.0.1:8080 {
        flush_interval -1
        transport http {
            versions 1.1
        }
    }
}

# Direct IP still works over HTTP (no TLS on bare IP).
http://${PUBLIC_HOST} {
    handle_path /downloads/* {
        root * ${DOWNLOAD_DIR}
        file_server
    }
    @metrics path /metrics
    respond @metrics 404
    @static path /downloads/*
    encode @static zstd gzip
    @websocket {
        path /api/ws
        header Connection *Upgrade*
        header Upgrade websocket
    }
    reverse_proxy @websocket 127.0.0.1:8080 {
        flush_interval -1
        transport http {
            versions 1.1
        }
    }
    reverse_proxy 127.0.0.1:8080 {
        flush_interval -1
        transport http {
            versions 1.1
        }
    }
}
EOF
caddy validate --config /etc/caddy/Caddyfile
systemctl restart caddy

if [[ -f "$ENV_FILE" ]]; then
  if grep -q '^FRONTEND_ORIGIN=' "$ENV_FILE"; then
    sed -i "s|^FRONTEND_ORIGIN=.*|FRONTEND_ORIGIN=${PUBLIC_URL}|" "$ENV_FILE"
  else
    printf 'FRONTEND_ORIGIN=%s\n' "$PUBLIC_URL" >>"$ENV_FILE"
  fi
  systemctl restart glagolitsa-api
fi

echo ""
echo "Проверка (после распространения DNS A → $PUBLIC_HOST):"
echo "  dig +short A ${PUBLIC_DOMAIN}"
echo "  curl -sf https://${PUBLIC_DOMAIN}/api/health"
echo ""
echo "В Android (gradle.properties):"
echo "  apiBaseUrl=https://${PUBLIC_DOMAIN}"
