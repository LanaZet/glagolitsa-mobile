-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Media Service: metadata only; encrypted blobs live in object storage.
-- Server never sees plaintext or file keys.

CREATE TABLE IF NOT EXISTS media_files (
    file_id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    chat_id UUID REFERENCES chats(id) ON DELETE SET NULL,
    kind TEXT NOT NULL CHECK (kind IN ('photo', 'document', 'voice', 'avatar', 'thumbnail', 'sticker')),
    mime_type TEXT NOT NULL DEFAULT 'application/octet-stream',
    storage_path TEXT NOT NULL UNIQUE,
    size_bytes BIGINT NOT NULL DEFAULT 0,
    size_bucket INT NOT NULL DEFAULT 0,
    content_hash_encrypted TEXT,
    parent_file_id UUID REFERENCES media_files(file_id) ON DELETE SET NULL,
    scan_status TEXT NOT NULL DEFAULT 'skipped' CHECK (scan_status IN ('pending', 'clean', 'rejected', 'skipped')),
    deleted_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_media_files_expires
    ON media_files (expires_at)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_media_files_owner
    ON media_files (owner_user_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_media_files_chat
    ON media_files (chat_id)
    WHERE chat_id IS NOT NULL AND deleted_at IS NULL;

-- Backfill legacy attachment slots into media_files.
INSERT INTO media_files (
    file_id, owner_user_id, kind, mime_type, storage_path,
    size_bytes, size_bucket, scan_status, expires_at, created_at
)
SELECT
    attachment_id,
    uploaded_by,
    'document',
    'application/octet-stream',
    object_key,
    size_bytes,
    size_bucket,
    'skipped',
    expires_at,
    created_at
FROM attachments
ON CONFLICT (file_id) DO NOTHING;