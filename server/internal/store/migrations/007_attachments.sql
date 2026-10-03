-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Encrypted attachment blobs in object storage (этап 6.1).
-- Сервер хранит только opaque metadata; содержимое — в MinIO.

CREATE TABLE IF NOT EXISTS attachments (
    attachment_id UUID PRIMARY KEY,
    object_key TEXT NOT NULL UNIQUE,
    size_bytes BIGINT NOT NULL,
    size_bucket INT NOT NULL,
    uploaded_by UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_attachments_expires
    ON attachments (expires_at);