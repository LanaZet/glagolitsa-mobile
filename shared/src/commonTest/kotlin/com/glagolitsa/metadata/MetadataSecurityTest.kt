// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Signal-style sealed payloads: server sees only opaque bytes + mailbox routing. */
class MetadataSecurityTest {
    @Test
    fun sealedDmPayload_withAttachmentRoundTrips() {
        val payload = SealedDmPayload(
            pairwise_id = "pair-1",
            sender_account_id = "sender",
            sender_device_id = "device",
            chat_id = "chat",
            body = "",
            attachment = SealedAttachmentRef(
                attachment_id = "att-1",
                file_key = "key-b64",
                file_name = "photo.jpg",
                mime_type = "image/jpeg",
                plaintext_size = 1024,
            ),
        )
        val decoded = decodeSealedDmPayload(encodeSealedDmPayload(payload))
        assertNotNull(decoded)
        assertEquals("att-1", decoded.attachment?.attachment_id)
    }

    @Test
    fun sealedGroupPayload_withAttachmentRoundTrips() {
        val payload = SealedGroupPayload(
            sender_account_id = "sender",
            sender_device_id = "device",
            chat_id = "group-1",
            body = "📎 photo.jpg",
            attachment = SealedAttachmentRef(
                attachment_id = "att-g1",
                file_key = "key-b64",
                file_name = "photo.jpg",
                mime_type = "image/jpeg",
                plaintext_size = 2048,
            ),
        )
        val decoded = decodeSealedGroupPayload(encodeSealedGroupPayload(payload))
        assertNotNull(decoded)
        assertEquals("att-g1", decoded.attachment?.attachment_id)
        assertEquals("photo.jpg", decoded.attachment?.file_name)
    }

    @Test
    fun sealedDmPayload_groupSkdmKindRoundTrips() {
        val payload = SealedDmPayload(
            pairwise_id = "pair-1",
            sender_account_id = "sender",
            sender_device_id = "device",
            chat_id = "chat",
            kind = PAYLOAD_KIND_GROUP_SKDM,
            group_skdm = "c2tt",
            group_chat_id = "group-chat",
        )
        val decoded = decodeSealedDmPayload(encodeSealedDmPayload(payload))
        assertNotNull(decoded)
        assertEquals(PAYLOAD_KIND_GROUP_SKDM, decoded.kind)
        assertEquals("group-chat", decoded.group_chat_id)
    }

    @Test
    fun sealedDmPayload_expiresAtSecRoundTrips() {
        val payload = SealedDmPayload(
            pairwise_id = "pair-1",
            sender_account_id = "sender",
            sender_device_id = "device",
            chat_id = "chat",
            body = "ttl message",
            expires_at_sec = 3600,
        )
        val decoded = decodeSealedDmPayload(encodeSealedDmPayload(payload))
        assertEquals(3600, decoded?.expires_at_sec)
    }

    @Test
    fun sealedDmPayload_readReceiptRoundTrips() {
        val payload = SealedDmPayload(
            pairwise_id = "pair-1",
            sender_account_id = "sender",
            sender_device_id = "device",
            chat_id = "chat",
            kind = PAYLOAD_KIND_DM_READ_RECEIPT,
            read_message_ids = listOf("env-1", "env-2"),
        )
        val decoded = decodeSealedDmPayload(encodeSealedDmPayload(payload))
        assertNotNull(decoded)
        assertEquals(PAYLOAD_KIND_DM_READ_RECEIPT, decoded.kind)
        assertEquals(listOf("env-1", "env-2"), decoded.read_message_ids)
    }

    @Test
    fun sealedDmPayload_withReplyFieldsRoundTrips() {
        val payload = SealedDmPayload(
            pairwise_id = "pair-1",
            sender_account_id = "sender",
            sender_device_id = "device",
            chat_id = "chat",
            body = "reply body",
            reply_to_message_id = "parent-1",
            reply_preview_sender_id = "sender-parent",
            reply_preview_body = "quoted body",
            visibility = "main",
        )
        val decoded = decodeSealedDmPayload(encodeSealedDmPayload(payload))
        assertNotNull(decoded)
        assertEquals("parent-1", decoded.reply_to_message_id)
        assertEquals("sender-parent", decoded.reply_preview_sender_id)
        assertEquals("quoted body", decoded.reply_preview_body)
        assertEquals("main", decoded.visibility)
        assertNull(decoded.thread_root_id)
    }

    @Test
    fun sealedGroupPayload_withThreadFieldsRoundTrips() {
        val payload = SealedGroupPayload(
            sender_account_id = "sender",
            sender_device_id = "device",
            chat_id = "group-1",
            body = "thread reply",
            thread_root_id = "root-1",
            thread_parent_id = "parent-1",
            reply_preview_sender_id = "thread-parent-sender",
            reply_preview_body = "thread quote",
            visibility = "thread_only",
        )
        val decoded = decodeSealedGroupPayload(encodeSealedGroupPayload(payload))
        assertNotNull(decoded)
        assertEquals("root-1", decoded.thread_root_id)
        assertEquals("thread-parent-sender", decoded.reply_preview_sender_id)
        assertEquals("thread quote", decoded.reply_preview_body)
        assertEquals("thread_only", decoded.visibility)
    }

    @Test
    fun extractPairwiseId_rejectsMissingPrefix() {
        assertNull(extractPairwiseId(byteArrayOf(1, 2, 3)))
    }
}
