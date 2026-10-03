# Channels architecture

**Status:** living doc (as-is + creative target)  
**Related:** [overview](overview.md) · [conversations](conversations.md) · [server channels](../../server/docs/channels/architecture.md)

---

## 1. Purpose

Channels are **broadcast conversations**: few publishers, many subscribers.  
Product mix for Glagolitsa creative direction:

| Source | Borrow |
|--------|--------|
| Telegram | type channel, `@slug`, admin-only post, album, discover |
| Discord | gallery grid, post-as-card, tags (later) |
| Slack | thread-only discussion, posting permissions |

Not a Discord Server (no categories/voice/N named roles in v1).

---

## 2. As-is (implemented)

### 2.1 Identity

- `chats.chat_type = 'channel'`
- Policies on `group_settings`: `visibility`, `slug`, `description`, `encryption_mode`
- Migration: `026_channels.sql`

### 2.2 Create presets (`group.Service.CreateChannel`)

| Setting | Value |
|---------|-------|
| type | channel |
| encryption | **none** |
| perm_send / invite / pin / moderate | **admin** |
| join_by_invite_only | true if private, false if public |
| slug | required + unique when public |

### 2.3 API (server)

| Method | Path | Role |
|--------|------|------|
| POST | `/api/channels` | create |
| GET | `/api/channels/slug-available?slug=` | check slug |
| POST | `/api/channels/slug/{slug}/join` | public subscribe |
| GET | `/api/channels/search?q=` | public discover |
| POST | `/api/groups` … | shared governance (members, invites, settings) |

Client: `MessengerRepository.createChannel`, `checkChannelSlugAvailable`, `joinPublicChannel`, search merge in list.

### 2.4 Roles and send

```
member  → may read (if joined), may not post when perm_send=admin
admin   → post, moderate (per perm_*), manage settings
```

Enforcement: `messaging.canSendToChat` + group package helpers.

### 2.5 Gaps vs creative v1

| Gap | Needed for |
|-----|------------|
| Open media thumbs/CDN ACL as first-class | gallery performance |
| Post metadata (album, cover) | multi-image work |
| Feed vs gallery queries (root + has media) | dual UI |
| Reactions | engagement |
| `perm_comment` / `perm_react` | policy |
| `ChannelScreen` Feed \| Gallery | product |

---

## 3. Target architecture (creative channel)

### 3.1 Logical layers

```
┌──────────────────────────────────────────────┐
│ UI: ChannelScreen                            │
│   Feed · Gallery · Lightbox · ComposePost    │
│   Thread (comments) · Settings               │
└──────────────────┬───────────────────────────┘
                   │
┌──────────────────▼───────────────────────────┐
│ Domain                                       │
│   CreativePost · permissions · gallery query │
└──────────────────┬───────────────────────────┘
                   │
┌──────────────────▼───────────────────────────┐
│ Data                                         │
│   messages (roots = posts, threads = comments)│
│   metadata (media[], tags)                   │
│   reactions · group_settings · members       │
│   open media files + thumbs                  │
└──────────────────────────────────────────────┘
```

### 3.2 Post model

A **post** is a root message in a channel (`thread_root_id` empty / self) with media metadata.

```
Post (root message)
├── caption
├── media[1..10]  file_id, w, h, cover?
├── tags[]          (v1.1)
├── reaction_summary
└── comment_count   (thread_reply_count)
```

Comments: messages with `thread_root_id = post_id`.

### 3.3 Dual view

| View | Query |
|------|-------|
| Feed | root posts by `created_at` DESC |
| Gallery | root posts with ≥1 image; cell = cover thumb |

Same data, different projection — no second storage entity required for v1.

### 3.4 Authorization matrix (target v1)

| Action | Admin | Member | Outsider |
|--------|-------|--------|----------|
| Discover public | ✓ | ✓ | ✓ (authed) |
| Join public | ✓ | ✓ | ✓ |
| Read after join | ✓ | ✓ | ✗ |
| Post to feed | ✓ | ✗ | ✗ |
| Comment | ✓ | if perm_comment | ✗ |
| React | ✓ | if perm_react | ✗ |
| Moderate | ✓ | ✗ | ✗ |

---

## 4. Media paths

### 4.1 E2E path (not for public channel)

Documented in [media-cache.md](../media-cache.md):

- encrypted blob on server
- local encrypted spool + optional encrypted thumb
- no server-side image processing of plaintext

### 4.2 Open path (channel) — implemented (v1)

```
Publisher
  → POST /api/media/upload-slots  { kind:photo, chat_id, content_mode:open }
  → PUT  /api/media/files/{id}    plaintext jpeg/png/webp (magic sniff)
  → POST /api/chats/{id}/messages { body, metadata.media:[{file_id, cover}] }
Subscriber
  → GET  /api/media/files/{id}    membership-gated, Content-Type = image/*
  → GET  /api/chats/{id}/gallery  roots with metadata.media
```

| Mode | `content_mode` | Who uploads | Who downloads |
|------|----------------|-------------|----------------|
| E2E (DM/group) | `encrypted` (default) | owner of slot | auth user (unchanged legacy) |
| Open (channel) | `open` | channel publisher | channel member only |

**Invariant:** `encryption_mode=none` + `type=channel` for open slots.  
**Invariant:** dm/group e2e path remains unchanged (`UploadEncryptedBlob` rejects open slots).  
**Gallery performance (implemented):**
- server JPEG thumb (`thumb_file_id`, max edge 320) on open upload (opaque→JPEG, alpha flatten)
- client LRU bitmap cache (64) + **disk cache** (`open-media/`, **200 MiB / 400 files LRU eviction**) + max 3 concurrent downloads
- decode with max edge (gallery 320 / feed 720 / lightbox 1440)
- LazyGrid only composes visible cells; **neighbor prefetch** ±1
- **progressive lightbox**: thumb → full; **pinch-zoom**, **swipe**, **comments** entry

**Product gates (implemented):**
- composer only for channel **admin/publisher** (`GET /api/groups/{id}` role)
- empty / offline error states on ChannelScreen
- desktop image picker (AWT FileDialog)

### 4.3 Limits (target)

| Limit | Value |
|-------|-------|
| Images / post | 10 |
| Max bytes / image | 15 MB |
| MIME | jpeg, png, webp |
| Max edge | 2560 |
| Caption | 4000 chars |

---

## 5. Client module target

```
shared/.../ui/channel/
  ChannelScreen.kt
  ChannelFeed.kt
  ChannelGallery.kt
  ComposeCreativePost.kt
  PostLightbox.kt
  ChannelPostCard.kt

shared/.../media/open/     # plaintext upload + URL resolve
```

Navigation: `ChatType.CHANNEL` → `ChannelScreen` (not plain chat chrome).

Dependencies (planned): Coil 3 CMP, FileKit / ImagePickerKMP — see implementation plan.

---

## 6. Server module map

| Package | Role |
|---------|------|
| `internal/group` | create channel, join, search, settings, members, invites, audit |
| `internal/messaging` | send gate, messages, threads |
| `internal/media` | slots, upload, CDN, retention; **extend** open photo |
| `internal/store` | Postgres/memory groups + migrations |
| `internal/model` | ChatTypeChannel, visibility, encryption constants |

Full server doc: [server/docs/channels/architecture.md](../../server/docs/channels/architecture.md).

---

## 7. Event flow (target publish)

```
UI ComposePost
  → upload open media (N)
  → POST post/message with media metadata
  → server authz admin + validate files
  → persist message root + metadata
  → WS message.new to members
  → optional push wake (no body) for offline
  → clients update feed + gallery projection
```

Comment:

```
UI Thread composer
  → POST message thread_root_id=post
  → perm_comment check
  → WS → bump comment_count on root
```

---

## 8. Testing anchors

| Layer | What |
|-------|------|
| Server unit | CreateChannel presets, slug unique, search, join, canSend |
| Smoke script | `scripts/verify-channel-discover-smoke.sh` |
| Client unit | `ChatCreationPolicy`, list filters, (later) gallery helpers |
| Creative (planned) | posts ACL, gallery query, reactions, open media ACL |

---

## 9. Decision log (architecture)

| ID | Decision |
|----|----------|
| A1 | Channel is product type + policies, not a separate storage product |
| A2 | Public/creative media is **not** e2e |
| A3 | Comments live in **threads**, not main feed |
| A4 | Gallery is a **view** over root posts with media |
| A5 | Creative v1 does not introduce Discord servers |

Target creative features (gallery, open media, reactions) are described as architecture targets above; product TZ / phased PR plan can be added later under `docs/channels/` when ready.
