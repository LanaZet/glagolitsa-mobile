#!/usr/bin/env bash

# Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
# See LICENSE for license information.

# Put api.glagolit.me behind Cloudflare orange-cloud; keep TURN grey-cloud
# so UDP/TLS still hits the VPS.
#
#   export CF_API_TOKEN=...   # Zone:Zone:Edit + Zone:DNS:Edit + Zone:SSL:Edit
#   ./scripts/setup-cloudflare-orange.sh
#
# After it prints nameservers, set them at GoDaddy (domain → DNS → Nameservers).
set -euo pipefail

CF_API="${CF_API:-https://api.cloudflare.com/client/v4}"
ZONE_NAME="${ZONE_NAME:-glagolit.me}"
: "${ORIGIN_IP:?Set ORIGIN_IP to the server address}"
API_NAME="${API_NAME:-api}"
TURN_NAME="${TURN_NAME:-turn}"
TURN_PRIMARY_NAME="${TURN_PRIMARY_NAME:-turn-primary}"

: "${CF_API_TOKEN:?set CF_API_TOKEN (Cloudflare API token with Zone + DNS edit)}"

cf() {
  local method="$1" path="$2" body="${3:-}"
  local args=(-sS -X "$method" -H "Authorization: Bearer ${CF_API_TOKEN}" -H "Content-Type: application/json")
  if [[ -n "$body" ]]; then
    args+=(-d "$body")
  fi
  curl "${args[@]}" "${CF_API}${path}"
}

json_get() {
  python3 -c 'import json,sys; d=json.load(sys.stdin); p=sys.argv[1].split(".");
v=d
for k in p:
    if isinstance(v, list):
        v=v[int(k)]
    else:
        v=v.get(k)
print("" if v is None else v)' "$1"
}

echo "==> Find or create zone ${ZONE_NAME}"
ZONES_JSON="$(cf GET "/zones?name=${ZONE_NAME}")"
ZONE_ID="$(printf '%s' "$ZONES_JSON" | json_get result.0.id)"
if [[ -z "$ZONE_ID" ]]; then
  echo "Creating zone (full setup). You must point GoDaddy NS at Cloudflare."
  CREATE_JSON="$(cf POST "/zones" "{\"name\":\"${ZONE_NAME}\",\"type\":\"full\"}")"
  ZONE_ID="$(printf '%s' "$CREATE_JSON" | json_get result.id)"
  if [[ -z "$ZONE_ID" ]]; then
    echo "$CREATE_JSON"
    echo "FAIL: could not create zone. Token needs Zone.Zone Write, or add glagolit.me in the dashboard."
    exit 1
  fi
fi
echo "zone_id=${ZONE_ID}"

upsert_record() {
  local rtype="$1" name="$2" content="$3" proxied="$4"
  local fqdn="$name"
  if [[ "$name" != "$ZONE_NAME" ]]; then
    fqdn="${name}.${ZONE_NAME}"
  fi
  local existing
  existing="$(cf GET "/zones/${ZONE_ID}/dns_records?type=${rtype}&name=${fqdn}")"
  local rid
  rid="$(printf '%s' "$existing" | json_get result.0.id)"
  local body
  body="$(python3 -c 'import json,sys; print(json.dumps({"type":sys.argv[1],"name":sys.argv[2],"content":sys.argv[3],"ttl":1,"proxied":sys.argv[4]=="true"}))' "$rtype" "$name" "$content" "$proxied")"
  if [[ -n "$rid" ]]; then
    echo "update ${rtype} ${fqdn} -> ${content} proxied=${proxied}"
    cf PUT "/zones/${ZONE_ID}/dns_records/${rid}" "$body" >/dev/null
  else
    echo "create ${rtype} ${fqdn} -> ${content} proxied=${proxied}"
    cf POST "/zones/${ZONE_ID}/dns_records" "$body" >/dev/null
  fi
}

echo "==> DNS records"
upsert_record A "$API_NAME" "$ORIGIN_IP" true
upsert_record A "$TURN_NAME" "$ORIGIN_IP" false
upsert_record A "$TURN_PRIMARY_NAME" "$ORIGIN_IP" false

echo "==> SSL Full (strict) so origin keeps its Let's Encrypt / origin cert"
cf PATCH "/zones/${ZONE_ID}/settings/ssl" '{"value":"strict"}' >/dev/null || true
cf PATCH "/zones/${ZONE_ID}/settings/always_use_https" '{"value":"on"}' >/dev/null || true
# WebSockets are on by default; keep explicit.
cf PATCH "/zones/${ZONE_ID}/settings/websockets" '{"value":"on"}' >/dev/null || true

echo "==> Nameservers (set these at GoDaddy)"
ZONE_JSON="$(cf GET "/zones/${ZONE_ID}")"
printf '%s' "$ZONE_JSON" | python3 -c '
import json,sys
d=json.load(sys.stdin)
res=d.get("result") or {}
nss=res.get("name_servers") or []
status=res.get("status")
print(f"zone_status={status}")
if not nss:
    print("(no nameservers in response)")
    sys.exit(0)
for ns in nss:
    print(ns)
'

echo ""
echo "GoDaddy: Domain → DNS → Nameservers → Change → enter the two Cloudflare NS."
echo "Keep turn.* grey-cloud (DNS only). api.* must stay orange-cloud."
echo "Check: dig +short A api.glagolit.me   # should be Cloudflare anycast, not ${ORIGIN_IP}"
echo "Check: dig +short A turn.glagolit.me  # must stay ${ORIGIN_IP}"
