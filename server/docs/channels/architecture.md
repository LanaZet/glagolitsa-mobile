# Server: channels & groups architecture

**Status:** living doc  
**Package:** `server/internal/group`, `messaging`, `media`, `store`  
**Client docs:** [docs/architecture/channels.md](../../../docs/architecture/channels.md)  
**Migration:** `internal/store/migrations/026_channels.sql`

---

## 1. Model

Product type lives on `chats.chat_type`:

| Value | Product |
|-------|---------|
| `dm` | direct message |
| `group` | multi-party e2e group |
| `channel` | broadcast; policies for public/private |

Governance for **group** and **channel** shares:

- `group_settings` (policies)
- `group_members` (role: `admin` | `member`)
- invites, join requests, bans, pins, audit events

### 1.1 Policy columns (`026_channels.sql`)

| Column | Values | Notes |
|--------|--------|-------|
| `visibility` | `private` \| `public` | discover gate |
| `slug` | unique when set | public address (`@slug`) |
| `description` | text | |
| `encryption_mode` | `e2e` \| `none` | channel create → **none** |

Plus existing:

- `perm_invite`, `perm_send_messages`, `perm_pin`, `perm_moderate` ∈ {`admin`,`all`}
- `join_by_invite_only`, `join_requests_enabled`
- `membership_version` (e2e groups)

### 1.2 Create presets

| | `CreateGroup` | `CreateChannel` |
|--|---------------|-----------------|
| chat_type | group | channel |
| visibility | private | public (default) or private |
| encryption | e2e | **none** |
| perm_send | all | **admin** |
| slug | — | required if public |

Code: `internal/group/service.go` (`CreateGroup`, `CreateChannel`).

---

## 2. HTTP surface

Registered under group/channel handlers (`internal/group/handler.go` and main router).

| Method | Path | Behavior |
|--------|------|----------|
| POST | `/api/channels` | create channel + settings presets |
| GET | `/api/channels/slug-available?slug=` | availability |
| POST | `/api/channels/slug/{slug}/join` | public join as member |
| GET | `/api/channels/search?q=` | public discover (auth) |
| POST | `/api/groups` | create private group |
| … | `/api/groups/{id}/…` | members, invites, settings, mute/ban/pin |

Messaging send path applies `perm_send_messages` via `internal/messaging/permissions.go` (`canSendToChat`).

---

## 3. Packages

```
internal/group/
  handler.go      HTTP
  service.go      CreateChannel, join, search, governance
  models.go       DTOs (CreateChannelRequest, ChannelResponse, settings)
  permissions.go  invite / moderate / pin helpers
  store.go        port on store.Store

internal/model/
  ChatTypeChannel, Visibility*, Encryption*

internal/store/
  group.go           GroupSettings, roles, perms
  postgres_group.go  persistence
  memory_group.go    tests
  migrations/026_channels.sql

internal/messaging/
  permissions.go     send gate for admin-only channels
  …                  messages + threads

internal/media/
  …                  file slots/upload/CDN (open path for channel — planned)
```

---

## 4. AuthZ rules (current)

1. JWT required for channel APIs.
2. Public search/join: any authenticated user.
3. Private channel: membership or invite.
4. Send message: if `perm_send_messages=admin`, caller must be `admin`.
5. Settings / ban / role changes: admin (see group service).

### 4.1 Planned (creative)

| Rule | Spec |
|------|------|
| Open media download | member (v1) |
| Thread comment | `perm_comment` |
| Reaction | `perm_react` |
| Post multi-media metadata | admin + valid file_ids |

See client [architecture/channels.md](../../../docs/architecture/channels.md).

---

## 5. Encryption implications

| encryption_mode | Server media | Fan-out |
|-----------------|--------------|---------|
| `e2e` | opaque ciphertext | membership_version / key rotation relevant |
| `none` | may process images (open path) | broadcast; no e2e key rotation |

**Do not** run e2e key-rotation requirements as hard failures for channel open path (join already softens this).

---

## 6. Discoverability

```
SearchPublicChannels(q)
  → chats where type=channel AND visibility=public
  → match title / slug
  → never return private groups/channels
```

Smoke: `scripts/verify-channel-discover-smoke.sh` (repo root).

---

## 7. Audit events (existing)

Examples from `group/models.go`:

- `group.created` (includes channel create metadata)
- `member.*`, `invite.*`, `join.*`
- `settings.updated`, `item.pinned`

Creative posts should extend with `post.created` / `post.deleted` when implemented.

---

## 8. Tests

| Area | Location |
|------|----------|
| Create channel public/private, slug clash, search | `group/service_test.go` |
| Send permissions | messaging tests / `permissions.go` |
| Migrations | apply `026_channels.sql` in deploy/dev |

---

## 9. Roadmap (server slice of creative plan)

| Step | Work |
|------|------|
| 1 | Open media upload + ACL for `encryption=none` |
| 2 | `metadata_json` on messages; feed/gallery queries |
| 3 | `perm_comment`, `perm_react`; reactions store |
| 4 | Rate limits on channel post/upload |

---

## 10. Related security docs

- [../security/auth-refresh-signal.md](../security/auth-refresh-signal.md)
- [../push/architecture.md](../push/architecture.md) — channel messages still wake-only push (no body)
