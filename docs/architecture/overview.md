# Architecture overview

**Status:** living doc  
**Scope:** Glagolitsa Mobile client + Go API (`server/`)  
**Related:** [channels](channels.md) · [conversations](conversations.md)

---

## 1. System shape

```
┌─────────────────────────────────────────────────────────────┐
│ Clients (KMP shared + platform shells)                      │
│  Android · Desktop · iOS (see docs/ios/)                    │
│  Compose UI · SqlDelight · Ktor · libsignal (DM/group e2e)  │
└────────────────────────────┬────────────────────────────────┘
                             │ HTTPS + WebSocket
┌────────────────────────────▼────────────────────────────────┐
│ Go API (server/)                                            │
│  identity · messaging · group/channel · media · presence    │
│  notification · calling · sync · jobs                       │
└────────────────────────────┬────────────────────────────────┘
                             │
          ┌──────────────────┼──────────────────┐
          ▼                  ▼                  ▼
     Postgres            Blobstore           Redis (optional)
   (truth + authz)     (media bytes)        (relay/cluster)
```

| Layer | Responsibility |
|-------|----------------|
| **UI** | Screens, navigation, optimistic UX |
| **Repository** | Session, sync, send/receive orchestration |
| **Local DB** | SqlDelight chats/messages/attachments metadata |
| **Crypto** | libsignal for e2e DM/group only |
| **API** | Auth, messaging, groups/channels, media, push registration |
| **Store** | Postgres membership, settings, messages, files |

---

## 2. Conversation model (product × policy)

Product surface uses three **types**. Internally, group/channel share a **governance shell** (`group_settings`, members, invites) with Matrix-like **policies**.

```
                    ┌──────── product type ────────┐
                    │  dm  │  group  │  channel    │
                    └───┬──┴────┬────┴──────┬──────┘
                        │       │           │
              pairwise  │       │           │ broadcast shell
                        │       ▼           ▼
                        │   group_settings policies
                        │   visibility · slug · encryption
                        │   perm_send · perm_invite · …
                        │
                        ▼
                   messages (+ threads)
                   media (path depends on encryption)
```

| Type | Members | Who posts (default) | Encryption | Discover |
|------|---------|---------------------|------------|----------|
| `dm` | 2 | both | **e2e** | no |
| `group` | N | all | **e2e** | no (invite) |
| `channel` | N subscribers | **admin only** | **none** | public via `@slug` |

Details: [conversations.md](conversations.md), [channels.md](channels.md).

---

## 3. Crypto and media paths

Two incompatible worlds — must not be mixed casually:

| Path | Used by | Blob on server | Client cache |
|------|---------|----------------|--------------|
| **E2E** | dm, group | opaque encrypted | [media-cache.md](../media-cache.md) |
| **Open** | channel (`encryption=none`) | processable image + thumbs/CDN | open media cache (design) |

```
Send media
  ├─ chat.encryption == e2e  → seal → /api/media encrypted upload
  └─ chat.encryption == none → open photo upload → thumbs → cdn_url
```

Creative channel **depends** on the open path (see channels architecture).

---

## 4. Client package map (shared)

```
com.glagolitsa
├── api/                 # Ktor ApiClient
├── auth/ session/
├── crypto/              # libsignal integration
├── db/                  # SqlDelight LocalDataStore
├── media/               # AttachmentCache (e2e); open path TBD
├── model/               # Chat, Message, policies
├── repository/          # MessengerRepository
├── ui/
│   ├── ChatListScreen, ChatScreen, CreateChannelScreen, …
│   ├── chat/            # message chrome, settings, threads
│   └── channel/         # TBD: Feed, Gallery, Lightbox (creative)
└── …
```

Server map: [server/docs/channels/architecture.md](../../server/docs/channels/architecture.md).

---

## 5. AuthZ sketch

```
Request
  → JWT / session
  → resource load (chat / file / group)
  → membership?
  → role + group_settings.perm_*
  → allow / 403
```

Channel feed post: `perm_send_messages=admin` ∧ `role=admin`.  
Channel media download (v1 target): membership required.

---

## 6. Sync and realtime

| Mechanism | Role |
|-----------|------|
| WebSocket | live `message.new`, chat updates, presence |
| REST pages | history, gallery cursor (planned) |
| Outbox jobs | reliable send (messages + attachments) |
| Push | wake-only when offline — [push architecture](../../server/docs/push/architecture.md) |

---

## 7. Documentation ownership

| Change | Update |
|--------|--------|
| New conversation type / policy | `architecture/conversations.md` + `channels.md` |
| E2E cache behavior | `media-cache.md` |
| Server group/channel handlers | `server/docs/channels/architecture.md` |
| iOS shell / APNs client / device install | [`docs/ios/`](../ios/README.md) (not this file) |
| Index links | `docs/README.md` + root `README.md` |
