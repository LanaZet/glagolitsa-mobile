# Mattermost patterns we borrow (security-safe)

Glagolitsa reuses **operational** and **platform** ideas from Mattermost server
architecture. We **do not** copy Mattermost’s server-visible message model or
push previews.

## Borrowed

| Pattern | Mattermost analogue | Glagolitsa | Security note |
|---------|---------------------|------------|---------------|
| Jobs + SKIP LOCKED claim | `channels/jobs` | `internal/jobs` + postgres jobs | No message content in job rows for push |
| Cluster WS fan-out | `platform/cluster` | `internal/cluster` Redis | Events stay opaque envelope ids |
| Metrics einterface | Prometheus wrappers | `internal/metrics` | Labels are type/platform, not bodies |
| Hooks | plugin multi-hook | `internal/hooks` | In-process only (no third-party RPC yet) |
| Support packet lite | diagnostics / support packet | `GET /api/diagnostics` | **No secrets, tokens, PII, ciphertext** |
| Push readiness | Push Proxy status | adapter mode: fcm/apns/log | Credentials never in response |
| HA push coalesce | multi-node coordination | Redis debounce `glag:push:debounce:*` | Key = user/device/type only |

## Explicitly rejected

| Mattermost behaviour | Why not |
|----------------------|---------|
| Push notification with message text / channel name from server | Breaks E2EE + arXiv FCM-leak posture |
| Indexing private messages in Elasticsearch | Server must not see plaintext DM content |
| God-object `app.App` dumping all domains | Prefer small domain services |

## Privacy invariant (push)

OSPNS (FCM/APNs/UnifiedPush) receives **opaque wake only** (`type`, `schema`,
optional opaque ids). User-visible copy is built **on device after decrypt**.

See also: `server/docs/push/architecture.md`, `server/docs/security/auth-refresh-signal.md`.
