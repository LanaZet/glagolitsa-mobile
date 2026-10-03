# Conversations: DM, group, channel

**Status:** living doc  
**Related:** [overview](overview.md) · [channels](channels.md)

---

## 1. Product types

| `Chat.type` | Product name | Create entry | Default encryption | Default who can send |
|-------------|--------------|--------------|--------------------|----------------------|
| `dm` | Личный чат | search user / «Написать» | e2e | participants |
| `group` | Группа | «Новая группа» | e2e | all members |
| `channel` | Канал | «Новый канал» | **none** | **admin only** |

Client constants: `com.glagolitsa.model.ChatType`.  
Server constants: `model.ChatTypeDM | ChatTypeGroup | ChatTypeChannel`.

---

## 2. Shared vs split storage

| Concern | DM | Group | Channel |
|---------|----|-------|---------|
| `chats` row | yes | yes | yes |
| `group_settings` | no | yes | yes |
| members table | implicit pair | `group_members` | `group_members` (subscribers) |
| invites / join requests | no | yes | yes (private) |
| public slug | no | no | optional (`visibility=public`) |
| e2e membership_version / key rotation | pairwise | yes | **no** (encryption=none) |

Migration for channel policies: `server/internal/store/migrations/026_channels.sql`.

---

## 3. Policy fields (`group_settings`)

| Field | Meaning | Group default | Channel default |
|-------|---------|---------------|-----------------|
| `visibility` | private \| public | private | public or private |
| `slug` | public address | empty | required if public |
| `description` | about text | optional | optional |
| `encryption_mode` | e2e \| none | e2e | **none** |
| `join_by_invite_only` | gate join | true-ish | true if private |
| `join_requests_enabled` | admin approve | configurable | configurable |
| `perm_invite` | admin \| all | … | admin |
| `perm_send_messages` | admin \| all | **all** | **admin** |
| `perm_pin` | admin \| all | … | admin |
| `perm_moderate` | admin \| all | … | admin |

**Planned (creative):** `perm_comment`, `perm_react` (see channels architecture).

---

## 4. Roles (current)

| Role | Store value | Notes |
|------|-------------|-------|
| Admin | `admin` | Creator is admin; client may track `creator_id` |
| Member | `member` | Channel: subscriber |

Not yet: granular bitflags, separate `owner` role, Discord-style named roles.  
Target evolution: Telegram-like admin rights — creative plan Phase 6+.

---

## 5. Messaging invariants

1. **DM / group e2e:** server stores ciphertext; client decrypts; media sealed (media-cache).
2. **Channel open:** server may store/process plaintext media; feed is broadcast.
3. **Threads:** `thread_root_id` / `thread_parent_id` / `thread_reply_count` — used for replies; creative comments = thread under post.
4. **Send gate:** `messaging.canSendToChat` honors `perm_send_messages`.
5. **Public discover:** only `visibility=public` channels with slug appear in search; private groups/channels must not.

---

## 6. Client UX map

| Type | Primary screen (today) | Target |
|------|------------------------|--------|
| dm | `ChatScreen` | same |
| group | `ChatScreen` | same + governance settings |
| channel | `ChatScreen` / create sheet | **`ChannelScreen`**: Feed \| Gallery + thread comments |

Create: `CreateChannelScreen` (Apple HIG sheet).  
List filters: `ChatListSearch` — All / Direct / Groups / Channels + public discover merge.

---

## 7. Anti-confusion rules

1. Do not encrypt public channel media with the DM sealed path.
2. Do not show private group/channel in global discover.
3. Do not treat `role=admin` as soft permission only — server must enforce send.
4. Do not put thread replies into gallery queries (roots only).
