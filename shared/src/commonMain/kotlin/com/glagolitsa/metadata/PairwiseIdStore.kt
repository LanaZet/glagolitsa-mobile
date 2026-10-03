// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.metadata

import com.glagolitsa.db.LocalDataStore
import com.glagolitsa.util.sha256

private const val PARTNER_PREFIX = "pairwise:partner:"
private const val REVERSE_PREFIX = "pairwise:id:"

class PairwiseIdStore(
    private val local: LocalDataStore,
) {
    /** Один opaque id на пару аккаунтов — совпадает у обоих клиентов. */
    suspend fun getOrCreate(selfAccountId: String, partnerAccountId: String): String {
        val key = PARTNER_PREFIX + partnerAccountId
        val existing = local.loadSetting(key)
        if (existing.isNotBlank()) {
            return existing
        }
        val pairwiseId = pairwiseIdForPair(selfAccountId, partnerAccountId)
        local.saveSetting(key, pairwiseId)
        local.saveSetting(REVERSE_PREFIX + pairwiseId, partnerAccountId)
        return pairwiseId
    }

    suspend fun partnerFor(pairwiseId: String): String? {
        val partner = local.loadSetting(REVERSE_PREFIX + pairwiseId)
        return partner.takeIf { it.isNotBlank() }
    }

}

fun pairwiseIdForPair(accountA: String, accountB: String): String {
    val canonical = listOf(accountA, accountB).sorted().joinToString("|")
    val digest = sha256(canonical.encodeToByteArray())
    val bytes = digest.copyOfRange(0, PAIRWISE_ID_BYTE_LENGTH)
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    return bytesToUuid(bytes)
}