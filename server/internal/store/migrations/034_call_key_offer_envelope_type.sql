-- Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
-- See LICENSE for license information.

-- Signal-encrypted call media key offers need the Signal envelope type to decrypt.
-- The encrypted_key remains opaque ciphertext; plaintext keys never touch storage.

ALTER TABLE call_key_offers
    ADD COLUMN IF NOT EXISTS envelope_type INT NOT NULL DEFAULT 0;
