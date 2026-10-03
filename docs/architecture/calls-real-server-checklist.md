# Calls: real-server test checklist

## 1. RTC + env + migration

```bash
# On ops machine (secrets in env, not git):
export LIVEKIT_API_KEY=...
export LIVEKIT_API_SECRET=...
export TURN_SHARED_SECRET=...
export TURN_DOMAIN=turn-primary.example
export LIVEKIT_URL=wss://rtc-primary.example
export LIVEKIT_NODE_IP=YOUR_PUBLIC_IP   # optional

./scripts/bootstrap-calls-rtc.sh
# deploy generated configs + docker compose RTC
# production.env: LIVEKIT_*, TURN_*, CALLS_RTC_AVAILABLE=true,
#   CLIENT_FEATURE_CALLS_RTC_LIVEKIT_ENABLED=true
# restart API (032 migration applies on start)
```

## 2. Smoke + diagnostics

```bash
API_BASE_URL=https://api.example ./scripts/verify-calls-ready.sh
LIVEKIT_HTTP_URL=http://127.0.0.1:7880 ./scripts/verify-rtc-edge-smoke.sh
```

Expect: `/api/health` ok, `rtc` subsystem not permanently `disabled`, features show livekit/audio_only when ready.

## 3. APK

```bash
./scripts/build-prerelease-apk.sh --api https://api.example
# optional: --install for adb phone
```

Install on **two** Android devices (or one device + emulator). Grant microphone.

## 4. 1:1 audio smoke

**API control plane (no media):**

```bash
API_BASE_URL=https://api.example \
  CALLER_USER=... CALLER_PASS=... CALLEE_USER=... CALLEE_PASS=... \
  ./scripts/smoke-call-1to1-api.sh
```

**Real media (manual):**

1. Device A login → open DM with B → Call  
2. Device B accept  
3. Two-way audio  
4. Mute / hangup both sides  
5. Optional: VPN / background  

## 5. Bug list template (after smoke)

| Bug | Severity | Logs (redacted) | Fix |
|-----|----------|-----------------|-----|
| | | no tokens/keys/IPs | |

## 6. Post-smoke polish (code in tree)

- WS `call.*` → `CallController.onControlEvent` (polling fallback kept)  
- Quality loop every 2s → low-bandwidth + notices  
- E2EE key generated before LiveKit connect when `media_config.e2ee`  
- Group invite `IsGroupMember` fail-closed  

Still manual/product: full LiveKit E2EE frame crypto provider wiring, group call UI sheet, captions.
