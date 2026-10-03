// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.util.KeyHelper
import java.util.UUID

/**
 * Roundtrip Sender Keys: Alice distributes, Bob processes, group encrypt/decrypt.
 */
internal object LibSignalGroupSelfTest {
    private val chatId = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val aliceAddress = org.signal.libsignal.protocol.SignalProtocolAddress("alice#device-a", 1)
    private val bobAddress = org.signal.libsignal.protocol.SignalProtocolAddress("bob#device-b", 1)

    fun run(): Boolean = runCatching {
        val aliceStore = InMemorySignalProtocolStore(
            IdentityKeyPair.generate(),
            KeyHelper.generateRegistrationId(false),
        )
        val bobStore = InMemorySignalProtocolStore(
            IdentityKeyPair.generate(),
            KeyHelper.generateRegistrationId(false),
        )

        val distribution = GroupSessionBuilder(aliceStore).create(aliceAddress, chatId)
        GroupSessionBuilder(bobStore).process(aliceAddress, distribution)

        val plaintext = "glagolitsa-group-self-test".toByteArray(Charsets.UTF_8)
        val encrypted = GroupCipher(aliceStore, aliceAddress).encrypt(chatId, plaintext)
        val decrypted = GroupCipher(bobStore, aliceAddress).decrypt(encrypted.serialize())

        decrypted.contentEquals(plaintext)
    }.getOrDefault(false)
}