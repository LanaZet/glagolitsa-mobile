#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Origin-side TURN/UDP + TURN/TLS on 443. Run on the VPS as root.
#
#   PUBLIC_DOMAIN=api.glagolit.me TURN_DOMAIN=turn.glagolit.me bash setup-turn-443.sh
#
# Layout after this script:
#   UDP/3478, UDP/443          coturn TURN
#   TCP/5349                   coturn TURNS (direct)
#   TCP/443 sslh SNI mux       turn.* → coturn:5349 ; everything else → Caddy:8443
#   TCP/80                     Caddy HTTP-01 + redirects
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Запусти от root"
  exit 1
fi

PUBLIC_DOMAIN="${PUBLIC_DOMAIN:-api.glagolit.me}"
TURN_DOMAIN="${TURN_DOMAIN:-turn.glagolit.me}"
: "${PUBLIC_HOST:?Set PUBLIC_HOST to the public address of this machine}"
ENV_FILE="${ENV_FILE:-/etc/glagolitsa/production.env}"
TURN_CONF="${TURN_CONF:-/root/glagolitsa-deploy/deploy/rtc/turnserver.conf}"
CADDY_FILE="${CADDY_FILE:-/etc/caddy/Caddyfile}"
DOWNLOAD_DIR="${REMOTE_DOWNLOAD_DIR:-/var/www/glagolitsa-downloads}"
TURN_CERT_DIR="${TURN_CERT_DIR:-/etc/glagolitsa/turn-certs}"
CADDY_HTTPS_PORT="${CADDY_HTTPS_PORT:-8443}"

set_kv() {
  local file="$1" key="$2" value="$3"
  if grep -q "^${key}=" "$file" 2>/dev/null; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$file"
  else
    printf '%s=%s\n' "$key" "$value" >>"$file"
  fi
}

ensure_line() {
  local file="$1" line="$2"
  grep -qxF "$line" "$file" 2>/dev/null || printf '%s\n' "$line" >>"$file"
}

echo "==> UFW: TURN / LiveKit media"
ufw allow 3478/tcp comment 'coturn TURN TCP' || true
ufw allow 3478/udp comment 'coturn TURN UDP' || true
ufw allow 443/udp comment 'coturn TURN UDP on 443' || true
ufw allow 5349/tcp comment 'coturn TURNS' || true
ufw allow 49160:49200/udp comment 'coturn relay' || true
ufw allow 50000:50100/udp comment 'livekit rtc' || true
ufw allow 7881/tcp comment 'livekit ice tcp' || true
ufw status numbered | sed -n '1,40p'

echo "==> coturn: UDP 443 + TLS 5349"
if [[ ! -f "$TURN_CONF" ]]; then
  echo "missing $TURN_CONF"
  exit 1
fi
grep -q '^alt-listening-port=443$' "$TURN_CONF" || echo 'alt-listening-port=443' >>"$TURN_CONF"
if grep -q '^realm=' "$TURN_CONF"; then
  sed -i "s|^realm=.*|realm=${TURN_DOMAIN}|" "$TURN_CONF"
else
  echo "realm=${TURN_DOMAIN}" >>"$TURN_CONF"
fi
if grep -q '^tls-listening-port=' "$TURN_CONF"; then
  sed -i 's|^tls-listening-port=.*|tls-listening-port=5349|' "$TURN_CONF"
else
  echo 'tls-listening-port=5349' >>"$TURN_CONF"
fi
mkdir -p "$TURN_CERT_DIR"
chmod 0755 "$TURN_CERT_DIR"
if [[ -f "${TURN_CERT_DIR}/fullchain.pem" && -f "${TURN_CERT_DIR}/privkey.pem" ]]; then
  set_kv "$TURN_CONF" cert "${TURN_CERT_DIR}/fullchain.pem"
  set_kv "$TURN_CONF" pkey "${TURN_CERT_DIR}/privkey.pem"
fi

echo "==> Caddy HTTPS on 127.0.0.1:${CADDY_HTTPS_PORT} (public 443 goes to sslh)"
mkdir -p "$DOWNLOAD_DIR"
chmod 0755 "$DOWNLOAD_DIR"
CADDY_BACKUP="${CADDY_FILE}.pre-turn443"
cp -a "$CADDY_FILE" "$CADDY_BACKUP"
cat >"$CADDY_FILE" <<EOF
{
    servers {
        protocols h1
    }
    http_port 80
    https_port ${CADDY_HTTPS_PORT}
}

http://${PUBLIC_DOMAIN} {
    redir https://${PUBLIC_DOMAIN}{uri} 308
}

http://${TURN_DOMAIN} {
    respond 404
}

https://${PUBLIC_DOMAIN} {
    bind 127.0.0.1
    handle_path /downloads/* {
        root * ${DOWNLOAD_DIR}
        file_server
    }
    @metrics path /metrics
    respond @metrics 404

    handle /rtc* {
        reverse_proxy 127.0.0.1:7880 {
            flush_interval -1
            transport http {
                versions 1.1
            }
        }
    }

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

https://${TURN_DOMAIN} {
    bind 127.0.0.1
    respond "turn-acme" 200
}

http://${PUBLIC_HOST} {
    handle_path /downloads/* {
        root * ${DOWNLOAD_DIR}
        file_server
    }
    @metrics path /metrics
    respond @metrics 404
    reverse_proxy 127.0.0.1:8080 {
        flush_interval -1
        transport http {
            versions 1.1
        }
    }
}
EOF
caddy validate --config "$CADDY_FILE"

echo "==> sslh SNI mux on :443"
export DEBIAN_FRONTEND=noninteractive
if ! command -v sslh-select >/dev/null 2>&1; then
  apt-get update -qq
  apt-get install -y sslh
fi
cat >/etc/sslh.cfg <<EOF
verbose: false;
foreground: true;
inetd: false;
numeric: true;
timeout: 2;
user: "sslh";
pidfile: "/run/sslh.pid";
listen:
(
    { host: "0.0.0.0"; port: "443"; }
);
protocols:
(
    { name: "tls"; host: "127.0.0.1"; port: "5349"; sni_hostnames: [ "${TURN_DOMAIN}" ]; log_level: 0; },
    { name: "tls"; host: "127.0.0.1"; port: "${CADDY_HTTPS_PORT}"; log_level: 0; },
    { name: "timeout"; host: "127.0.0.1"; port: "${CADDY_HTTPS_PORT}"; }
);
on-timeout: "timeout";
EOF
mkdir -p /etc/systemd/system/sslh.service.d
cat >/etc/systemd/system/sslh.service.d/override.conf <<'EOF'
[Service]
EnvironmentFile=
ExecStart=
ExecStart=/usr/sbin/sslh-select --foreground -F/etc/sslh.cfg
EOF
systemctl daemon-reload

rollback_caddy_public_443() {
  echo "ROLLBACK: restoring Caddy on public :443"
  systemctl stop sslh 2>/dev/null || true
  if [[ -f "$CADDY_BACKUP" ]]; then
    cp -a "$CADDY_BACKUP" "$CADDY_FILE"
  fi
  systemctl restart caddy
}

echo "==> switch listeners (brief HTTPS blip)"
systemctl stop caddy || true
if ! systemctl restart sslh && ! systemctl restart sslh.service; then
  echo "sslh failed to start"
  rollback_caddy_public_443
  exit 1
fi
sleep 1
if ! systemctl start caddy; then
  echo "caddy failed after sslh"
  rollback_caddy_public_443
  exit 1
fi
sleep 2
if ! curl -fsS --connect-timeout 5 --max-time 10 "https://${PUBLIC_DOMAIN}/api/health" >/dev/null; then
  echo "public HTTPS health failed after mux"
  rollback_caddy_public_443
  exit 1
fi
echo "OK public HTTPS through sslh"

echo "==> copy TURN certs from Caddy when present"
cat >/usr/local/sbin/glagolitsa-sync-turn-certs.sh <<EOF
#!/usr/bin/env bash
set -euo pipefail
SRC_ROOT="/var/lib/caddy/.local/share/caddy/certificates"
DEST="${TURN_CERT_DIR}"
mkdir -p "\$DEST"
mapfile -t chains < <(find "\$SRC_ROOT" -path "*${TURN_DOMAIN}*" -name fullchain.pem 2>/dev/null | head -1)
if [[ \${#chains[@]} -eq 0 || -z "\${chains[0]:-}" ]]; then
  exit 0
fi
dir="\$(dirname "\${chains[0]}")"
install -m 0644 "\$dir/fullchain.pem" "\$DEST/fullchain.pem"
install -m 0640 "\$dir/${TURN_DOMAIN}.key" "\$DEST/privkey.pem" 2>/dev/null || \
  install -m 0640 "\$dir/privkey.pem" "\$DEST/privkey.pem"
chown root:turnserver "\$DEST/privkey.pem" 2>/dev/null || chown root:root "\$DEST/privkey.pem"
EOF
chmod 0755 /usr/local/sbin/glagolitsa-sync-turn-certs.sh
/usr/local/sbin/glagolitsa-sync-turn-certs.sh || true

echo "==> restart coturn"
if docker ps --format '{{.Names}}' | grep -q coturn; then
  docker restart "$(docker ps --format '{{.Names}}' | grep coturn | head -1)" || true
else
  systemctl restart coturn 2>/dev/null || true
  # host-network compose service
  if [[ -f /root/glagolitsa-deploy/deploy/rtc/docker-compose.rtc.yml ]]; then
    docker restart rtc-coturn-1 2>/dev/null || true
  fi
fi

if [[ -f "$ENV_FILE" ]]; then
  echo "==> production.env ICE URLs"
  set_kv "$ENV_FILE" TURN_DOMAIN "$TURN_DOMAIN"
  # Keep the origin IP as an extra ICE URL until turn.* DNS exists.
  set_kv "$ENV_FILE" TURN_URL "turn:${PUBLIC_HOST}:3478?transport=udp"
  set_kv "$ENV_FILE" STUN_SERVERS "stun:${PUBLIC_HOST}:3478,stun:${TURN_DOMAIN}:3478"
  set_kv "$ENV_FILE" FRONTEND_ORIGIN "https://${PUBLIC_DOMAIN}"
  systemctl restart glagolitsa-api
fi

echo "Done. TURN/TLS 443 needs DNS A ${TURN_DOMAIN} → ${PUBLIC_HOST} (grey cloud) and a Caddy cert."
