// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.jobs

import com.glagolitsa.metadata.SealedAttachmentRef
import com.glagolitsa.model.MessageRelationDraft
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object OutboxJobType {
    const val SEND_DM = "send_dm"
    const val SEND_GROUP = "send_group"
    const val SEND_DM_ATTACHMENT = "send_dm_attachment"
    const val SEND_GROUP_ATTACHMENT = "send_group_attachment"
}

object OutboxJobStatus {
    const val PENDING = "pending"
    const val PROCESSING = "processing"
}

data class OutboxJobRecord(
    val id: String,
    val jobType: String,
    val chatId: String,
    val payloadJson: String,
    val status: String,
    val attempts: Int,
    val nextAttemptAt: String?,
    val createdAt: String,
    val lastError: String?,
)

data class OutboxProcessingResult(
    val attempted: Int = 0,
    val sent: Int = 0,
    val transientFailures: Int = 0,
    val permanentFailures: Int = 0,
    val authDeferred: Boolean = false,
    val lastError: String? = null,
) {
    val hasFailures: Boolean
        get() = transientFailures > 0 || permanentFailures > 0 || authDeferred

    operator fun plus(other: OutboxProcessingResult): OutboxProcessingResult =
        OutboxProcessingResult(
            attempted = attempted + other.attempted,
            sent = sent + other.sent,
            transientFailures = transientFailures + other.transientFailures,
            permanentFailures = permanentFailures + other.permanentFailures,
            authDeferred = authDeferred || other.authDeferred,
            lastError = other.lastError ?: lastError,
        )
}

data class OutboxRecoveryResult(
    val requeued: Int = 0,
    val outbox: OutboxProcessingResult = OutboxProcessingResult(),
)

@Serializable
data class OutboxSendPayload(
    val chat_id: String,
    val body: String,
    val pending_id: String,
    val job_type: String,
    val relation: MessageRelationDraft? = null,
    val expires_at_sec: Long? = null,
    val attachment: SealedAttachmentRef? = null,
    val attachment_id: String? = null,
    val local_attachment_cache_id: String? = null,
    /** Legacy fallback for already persisted jobs before media spool existed. */
    val encrypted_attachment_base64: String? = null,
)

private val outboxJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

fun encodeOutboxPayload(payload: OutboxSendPayload): String = outboxJson.encodeToString(OutboxSendPayload.serializer(), payload)

fun decodeOutboxPayload(raw: String): OutboxSendPayload = outboxJson.decodeFromString(OutboxSendPayload.serializer(), raw)

fun outboxRetryDelayMs(attempts: Int): Long {
    val capped = attempts.coerceAtMost(10)
    val delay = 5_000L * (1L shl capped)
    return delay.coerceAtMost(30 * 60 * 1000L)
}

const val OUTBOX_MAX_ATTEMPTS = 12

/** Ошибки outbox, при которых job можно сразу повторить без backoff. */
object OutboxRecoverableErrors {
    const val NO_USER = "No user"
    const val NO_ENVELOPES = "No encrypted envelopes produced"
    const val NO_DEVICES = "Partner has no registered devices"

    /**
     * Partner has no mailbox yet. Not a send failure: keep the job and show the
     * relay-queued tick (one check). Delivery happens when a device appears.
     */
    fun isWaitingForRecipient(message: String?): Boolean {
        val text = message?.trim()?.lowercase().orEmpty()
        if (text.isEmpty()) return false
        return text.contains("partner has no registered devices") ||
            text.contains("no registered devices") ||
            text.contains("у контакта нет зарегистрированных устройств")
    }
}
