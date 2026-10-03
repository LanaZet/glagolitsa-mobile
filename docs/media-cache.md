# Client media cache

## Why this exists

Glagolitsa messages are E2EE. The server stores encrypted media blobs and opaque
metadata, but the client still needs a local media layer for reliable outbox,
offline reuse, previews, retention, and user-visible storage controls.

The first implementation keeps message rows in SQLDelight and media bytes as
separate private files. `outbox_jobs.payload_json` stores only metadata and a
local cache id, not the attachment blob itself.

## Design principles

- Metadata lives in SQLDelight (`local_attachments`).
- Media bytes live outside SQLite as private app files.
- Disk cache stores encrypted blobs, not full plaintext files.
- Plaintext is produced only when needed for preview/opening and is held in
  memory by default.
- Transfer state is explicit: `pending`, `uploading`, `uploaded`,
  `downloading`, `downloaded`, `failed`, `pinned`.
- Server `attachment_id` is the stable remote key; local outgoing jobs use a
  deterministic `cache_id` derived from `pending_id` until upload succeeds.
- Retention is local and policy-driven: max bytes, TTL/expiry, and immunity
  delay for recently created files.

## Current stage

Implemented:

- `com.glagolitsa.media.AttachmentCache`
- `AttachmentTransferManager`
- `AttachmentMetadataStore`
- `PlatformMediaFileStore`
- SQLDelight `local_attachments`
- outgoing encrypted spool files for attachment outbox jobs
- Android picker-to-cache streaming path that writes encrypted spool files without
  materializing the selected attachment as a full plaintext `ByteArray`
- file-backed chunk upload from the encrypted cache spool
- encrypted download cache keyed by `attachment_id`
- encrypted thumbnail files with thumbnail keys stored in SQLCipher metadata
- storage optimizer policy: max bytes, max file count, TTL, MIME type filters,
  excluded chats, and immunity delay
- clear all cache and clear cache for a specific chat
- profile settings storage screen for total cache size, refresh, optimize, and
  clear-all controls
- per-chat storage settings: cache size, refresh, clear-chat, and local
  `keep media` policy (`default`, `7d`, `30d`, `forever`)
- media validation boundary before image preview/thumbnail decoding: file size,
  filename, MIME normalization, and image signature checks
- Android encrypted backup/export includes the media-cache folder

The old `encrypted_attachment_base64` field remains as a compatibility fallback
for outbox jobs created before this cache existed.

## Next stages

1. Replace remaining legacy/desktop `ByteArray` attachment paths with platform
   streaming sources.
2. Apply per-chat retention automatically from background cache maintenance.
3. Add restore/export coverage for per-account hashed DB files once multi-account
   backup format is finalized.
4. Add per-chat retention policy and pinned media.
5. Move legacy `/api/attachments` client calls to `/api/media/files` once the
   server and client payload shape are aligned.

## Safety rules

- Do not write unencrypted full attachments to disk.
- Do not use convergent encryption or server-visible plaintext hashes for
  deduplication.
- Do not let OS cache cleanup delete pending upload blobs.
- Do not delete media for disappearing messages later than message expiry.
- Treat media parsing/thumbnail generation as a security boundary.
- Do not run image decoders for files that only claim to be images; require a
  supported MIME type and matching magic bytes first.
