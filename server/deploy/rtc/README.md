# RTC edge deployment (stage 1)

Audio-first calls need a media plane separate from the Go API:

| Component | Role |
|-----------|------|
| LiveKit SFU | Encrypted WebRTC media + WSS signaling |
| Redis | Production LiveKit room/node state |
| coturn | TURN/UDP and TURN/TLS relay for VPN / blocked UDP |
| Caddy (or equivalent) | TLS termination for `rtc-*.` WSS on 443 |

Go API remains control plane only (tokens, ICE config, call metadata). It must not receive plaintext media.

## Goals for this stage

1. Two test clients can join one LiveKit room.
2. TURN/UDP works (prefer port 443 when possible).
3. TURN/TLS works on 443 as fallback.
4. With UDP blocked, relay path still works.
5. Health checks distinguish **SFU up** from **TURN broken**.

## DNS (single region, multi-region-ready names)

| Name | Target |
|------|--------|
| `rtc-<region>.example` | LiveKit WSS entry |
| `turn-<region>.example` | coturn |
| `rtc.example` | Future geo/latency entrypoint (CNAME later) |

Example region id: `primary` or `ru-msk` (`RTC_REGION_ID`).

## Firewall / security groups

Open only what clients need:

| Port | Proto | Service |
|------|-------|---------|
| 443 | TCP | HTTPS/WSS (api via Caddy or Cloudflare) and TURN/TLS via SNI `turn.*` |
| 443 | UDP | TURN/UDP (does not conflict with HTTPS TCP/443) |
| 3478 | UDP/TCP | TURN |
| 5349 | TCP | TURNS backup if SNI mux is down |
| 50000–50100 | UDP | LiveKit WebRTC media (adjust to `livekit.yaml`) |
| 49160–49200 | UDP | coturn relay range (adjust to turnserver.conf) |

Keep closed from the public internet:

- Redis
- LiveKit raw HTTP `7880` (loopback / private only)
- Postgres, Go API admin, metrics scrapers as already designed

## Bootstrap

```bash
cd server/deploy/rtc
cp .env.example .env
cp turnserver.conf.example turnserver.conf
# edit livekit.yaml keys + node_ip / use_external_ip
# edit turnserver.conf external-ip + static-auth-secret
chmod 600 .env turnserver.conf
docker compose -f docker-compose.rtc.yml up -d
```

Wire Go API env (see `server/deploy/production.env.example`):

```bash
RTC_REGION_ID=primary
LIVEKIT_URL=wss://rtc-primary.example
LIVEKIT_API_KEY=...
LIVEKIT_API_SECRET=...
TURN_DOMAIN=turn-primary.example
TURN_SHARED_SECRET=...   # same as coturn static-auth-secret
CALLS_RTC_AVAILABLE=true
CLIENT_FEATURE_CALLS_RTC_LIVEKIT_ENABLED=true
CLIENT_FEATURE_CALLS_AUDIO_ONLY_ENABLED=true
```

## Health checks

| Check | How | Pass means |
|-------|-----|------------|
| LiveKit HTTP | `GET` loopback `:7880` or compose healthcheck | SFU process answers |
| WSS | client/smoke TLS connect to `wss://rtc-...` | Signaling path works |
| TURN/UDP allocate | smoke script / `turnutils_uclient` | Relay UDP works |
| TURN/TLS allocate | smoke script with turns:443 | Firewall-friendly fallback |

API diagnostics (`GET /api/diagnostics`) reports an `rtc` subsystem:

- `ok` — LiveKit configured and HTTP health reachable; TURN domain set
- `degraded` — keys set but SFU HTTP not reachable
- `disabled` — LiveKit not configured (fail-closed call flags)

Diagnostics **never** include tokens, TURN credentials, media keys, peer IPs, or room JWTs.

## Metrics labels (ops)

When scraping call/RTC metrics, include:

- `rtc_region`
- `turn_region`
- `candidate_type` (host/srflx/relay)
- `candidate_protocol` (udp/tcp/tls)
- `route_class` (single_region / …)
- `vpn_detected` (client-reported, coarse bool)

Do not label metrics with user id, username, phone, or full ICE candidate strings.

## Smoke tests

```bash
# From repo root (requires curl; optional turnutils for full TURN allocate)
./scripts/verify-rtc-edge-smoke.sh
```

Scenarios to run manually after deploy:

1. Normal Wi-Fi / cellular
2. UDP blocked (expect TURN/TLS or TURN/TCP)
3. VPN on one or both peers
4. Log review: no tokens, TURN secrets, or media keys

## Kill switch

If the RTC edge is unhealthy, set on the Go API host:

```bash
CALLS_RTC_AVAILABLE=false
# or remove LIVEKIT_API_KEY / LIVEKIT_API_SECRET
systemctl restart glagolitsa-api
```

Clients receive feature flags with LiveKit/audio-only off and hide start-call buttons.

## Out of scope for stage 1

- Android LiveKit media engine (stage 5)
- Short-lived TURN credential issuance polish (stage 4)
- Group-ready DB model (stage 2)
- Multi-region routing logic (stage 11)
