# Calls release runbook (stage 13)

## Feature rollout

1. Internal dogfood (`CALLS_RTC_AVAILABLE=true`, LiveKit flags on for staff).
2. Audio-only beta (small percentage / feature flag).
3. Audio-only public.
4. Adaptive video beta.
5. Group call beta (`calls_group_ready_api_enabled`).

## Kill switches

| Switch | Effect |
|--------|--------|
| `CALLS_RTC_AVAILABLE=false` | Hide start-call UI; CreateCall/token return 503 |
| Unset `LIVEKIT_API_KEY/SECRET` | Fail-closed RTC |
| `CLIENT_FEATURE_CALLS_VIDEO=false` | Audio only |
| `CLIENT_FEATURE_CALLS_GROUP_READY_API_ENABLED=false` | No group invites |

## Observability

Track (no PII labels):

- call setup success rate
- time to media connected
- reconnect count
- selected candidate type/protocol
- route class / rtc_region
- downgrade count / audio-only survival
- crash-free call sessions

## Privacy review checklist

- [ ] Logs: no tokens, TURN credentials, media keys, peer IPs, display names
- [ ] Metrics labels: region/candidate only
- [ ] Push: wake-only `type` + `call_id` + `collapse_id`
- [ ] Diagnostics upload: consent/debug only
- [ ] LiveKit webhooks: HMAC verified; body not logged
- [ ] Egress/recording disabled for private calls

## Capacity matrix

| Scenario | Notes |
|----------|-------|
| 1:1 audio 30 min | Definition of Done |
| 1:1 video | After audio-stable |
| Group audio 4–8 | Stage 10 limits |
| Group video publishers 2–4 | Rest audio-only |
| TURN/TLS only | VPN / blocked UDP |

## Incident runbooks

| Incident | Action |
|----------|--------|
| RTC edge down | `CALLS_RTC_AVAILABLE=false`; fix LiveKit; re-enable |
| TURN broken | Check coturn allocate smoke; keep audio-only |
| High packet loss region | Mark route degraded; delay video offers |
| Token issuing failure | Check API keys; rotate without logging secrets |
| Webhook lag | Reconcile via timeout sweep |
| Bad release | Roll back binary; kill switch first |

## Pre-release manual script

1. Two devices, 30-minute audio call.
2. Weak network → emergency voice, not hard drop.
3. VPN + TURN/TLS path.
4. `markCallConnected` only after media path.
5. Log scan for secrets/PII.
6. E2EE key offers only for joined devices.
