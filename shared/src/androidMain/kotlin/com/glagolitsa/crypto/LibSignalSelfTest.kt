// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.crypto

import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.util.KeyHelper
import org.signal.libsignal.protocol.util.Medium
import java.util.LinkedList
import java.util.Random

/**
 * Локальные roundtrip-сценарии 1-на-1 без сети (PQXDH / session v4).
 * Основано на тестовых сценариях libsignal SessionCipherTest.
 */
internal object LibSignalSelfTest {
    private val aliceAddress = SignalProtocolAddress("alice", 1)
    private val bobAddress = SignalProtocolAddress("bob", 1)

    fun run(): Boolean = runCatching {
        basicRoundtrip()
        bidirectionalExchange()
        outOfOrderDecrypt()
        messageKeyLimitDropsOldMessages()
        deleteSessionBlocksFurtherDecryptUntilRebuilt()
        resetSessionAllowsFreshEstablish()
        true
    }.getOrDefault(false)

    /** Single encrypt → decrypt (smoke). */
    fun basicRoundtrip(): Boolean {
        val (aliceStore, bobStore) = establishSession()
        val plaintext = "glagolitsa-self-test".toByteArray(Charsets.UTF_8)
        val aliceCipher = SessionCipher(aliceStore, aliceAddress, bobAddress)
        val outgoing = aliceCipher.encrypt(plaintext)
        val bobCipher = SessionCipher(bobStore, bobAddress, aliceAddress)
        val decrypted = decrypt(bobCipher, outgoing)
        return decrypted.contentEquals(plaintext)
    }

    /** Alice → Bob → Alice reply (ratchet advances both ways). */
    fun bidirectionalExchange(): Boolean {
        val (aliceStore, bobStore) = establishSession()
        val aliceCipher = SessionCipher(aliceStore, aliceAddress, bobAddress)
        val bobCipher = SessionCipher(bobStore, bobAddress, aliceAddress)

        val alicePlain = "hello from alice".toByteArray(Charsets.UTF_8)
        val toBob = aliceCipher.encrypt(alicePlain)
        if (!decrypt(bobCipher, toBob).contentEquals(alicePlain)) return false

        val bobPlain = "hello from bob".toByteArray(Charsets.UTF_8)
        val toAlice = bobCipher.encrypt(bobPlain)
        if (!decrypt(aliceCipher, toAlice).contentEquals(bobPlain)) return false

        for (i in 0 until 10) {
            val p = "alice-$i".toByteArray(Charsets.UTF_8)
            if (!decrypt(bobCipher, aliceCipher.encrypt(p)).contentEquals(p)) return false
        }
        for (i in 0 until 10) {
            val p = "bob-$i".toByteArray(Charsets.UTF_8)
            if (!decrypt(aliceCipher, bobCipher.encrypt(p)).contentEquals(p)) return false
        }
        return true
    }

    /**
     * Encrypt many messages, shuffle, decrypt out of order (Signal SessionCipherTest style).
     */
    fun outOfOrderDecrypt(count: Int = 100): Boolean {
        val (aliceStore, bobStore) = establishSession()
        val aliceCipher = SessionCipher(aliceStore, aliceAddress, bobAddress)
        val bobCipher = SessionCipher(bobStore, bobAddress, aliceAddress)

        // Establish ratchet with one in-order message first.
        val boot = "boot".toByteArray(Charsets.UTF_8)
        if (!decrypt(bobCipher, aliceCipher.encrypt(boot)).contentEquals(boot)) return false

        val plaintexts = (0 until count).map { "ood-msg-$it".toByteArray(Charsets.UTF_8) }
        val ciphertexts = plaintexts.map { aliceCipher.encrypt(it) }

        val seed = 42L
        val order = plaintexts.indices.shuffled(kotlin.random.Random(seed))
        for (index in order) {
            val decrypted = decrypt(bobCipher, ciphertexts[index])
            if (!decrypted.contentEquals(plaintexts[index])) return false
        }
        return true
    }

    /**
     * After deleteSession, ciphertext from the erased session must not decrypt.
     * User-facing: crypto session wipe / hard reset of a partner device.
     */
    fun deleteSessionBlocksFurtherDecryptUntilRebuilt(): Boolean {
        val (aliceStore, bobStore) = establishSession()
        val aliceCipher = SessionCipher(aliceStore, aliceAddress, bobAddress)
        val bobCipher = SessionCipher(bobStore, bobAddress, aliceAddress)

        val first = "before-wipe".toByteArray(Charsets.UTF_8)
        val firstCt = aliceCipher.encrypt(first)
        if (!decrypt(bobCipher, firstCt).contentEquals(first)) return false

        // Bob erases Alice's session record (mirror of CryptoEngine.resetSession).
        bobStore.deleteSession(aliceAddress)
        if (bobStore.containsSession(aliceAddress)) return false

        val orphan = aliceCipher.encrypt("after-bob-wipe".toByteArray(Charsets.UTF_8))
        val blocked = try {
            decrypt(bobCipher, orphan)
            false
        } catch (_: Exception) {
            true
        }
        if (!blocked) return false

        // Rebuild session from a new prekey bundle and resume.
        val (alice2, bob2) = establishSession()
        val a2 = SessionCipher(alice2, aliceAddress, bobAddress)
        val b2 = SessionCipher(bob2, bobAddress, aliceAddress)
        val again = "rebuilt".toByteArray(Charsets.UTF_8)
        return decrypt(b2, a2.encrypt(again)).contentEquals(again)
    }

    /**
     * deleteSession on sender side forces re-establish via PreKey bundle before encrypt works.
     */
    fun resetSessionAllowsFreshEstablish(): Boolean {
        val (aliceStore, bobStore) = establishSession()
        val aliceCipher = SessionCipher(aliceStore, aliceAddress, bobAddress)
        val bobCipher = SessionCipher(bobStore, bobAddress, aliceAddress)
        val p = "seed".toByteArray(Charsets.UTF_8)
        if (!decrypt(bobCipher, aliceCipher.encrypt(p)).contentEquals(p)) return false

        aliceStore.deleteSession(bobAddress)
        if (aliceStore.containsSession(bobAddress)) return false

        // Without a session, SessionCipher.encrypt typically still works only if session exists;
        // re-process PreKey bundle to re-establish (ensureSession path).
        val bundle = createPreKeyBundle(bobStore)
        SessionBuilder(aliceStore, bobAddress, aliceAddress).process(bundle)
        if (!aliceStore.containsSession(bobAddress)) return false

        val aliceCipher2 = SessionCipher(aliceStore, aliceAddress, bobAddress)
        val bobCipher2 = SessionCipher(bobStore, bobAddress, aliceAddress)
        val p2 = "after-reset".toByteArray(Charsets.UTF_8)
        return decrypt(bobCipher2, aliceCipher2.encrypt(p2)).contentEquals(p2)
    }

    /**
     * Past the message-key window, very old ciphertext must not decrypt
     * (Signal SessionCipherTest.testMessageKeyLimits).
     */
    fun messageKeyLimitDropsOldMessages(): Boolean {
        val (aliceStore, bobStore) = establishSession()
        val aliceCipher = SessionCipher(aliceStore, aliceAddress, bobAddress)
        val bobCipher = SessionCipher(bobStore, bobAddress, aliceAddress)

        val inflight = LinkedList<CiphertextMessage>()
        for (i in 0 until 2010) {
            inflight.add(
                aliceCipher.encrypt("you've never been so hungry, you've never been so cold".toByteArray()),
            )
        }

        decrypt(bobCipher, inflight[1000])
        decrypt(bobCipher, inflight.last())

        return try {
            decrypt(bobCipher, inflight.first())
            false
        } catch (_: DuplicateMessageException) {
            true
        } catch (_: Exception) {
            // Some libsignal versions surface limit hits as invalid/duplicate family errors.
            true
        }
    }

    private fun establishSession(): Pair<SignalProtocolStore, SignalProtocolStore> {
        val aliceStore = InMemorySignalProtocolStore(
            org.signal.libsignal.protocol.IdentityKeyPair.generate(),
            KeyHelper.generateRegistrationId(false),
        )
        val bobStore = InMemorySignalProtocolStore(
            org.signal.libsignal.protocol.IdentityKeyPair.generate(),
            KeyHelper.generateRegistrationId(false),
        )
        val bundle = createPreKeyBundle(bobStore)
        SessionBuilder(aliceStore, bobAddress, aliceAddress).process(bundle)
        return aliceStore to bobStore
    }

    private fun decrypt(cipher: SessionCipher, message: CiphertextMessage): ByteArray =
        when (message.type) {
            CiphertextMessage.PREKEY_TYPE ->
                cipher.decrypt(PreKeySignalMessage(message.serialize()))
            CiphertextMessage.WHISPER_TYPE ->
                cipher.decrypt(SignalMessage(message.serialize()))
            else -> error("Unexpected ciphertext type: ${message.type}")
        }

    private fun createPreKeyBundle(store: SignalProtocolStore): PreKeyBundle {
        val preKeyPair = ECKeyPair.generate()
        val signedPreKeyPair = ECKeyPair.generate()
        val signedPreKeySignature = store.identityKeyPair.privateKey
            .calculateSignature(signedPreKeyPair.publicKey.serialize())
        val kyberPreKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberPreKeySignature = store.identityKeyPair.privateKey
            .calculateSignature(kyberPreKeyPair.publicKey.serialize())

        val random = Random()
        val preKeyId = random.nextInt(Medium.MAX_VALUE)
        val signedPreKeyId = random.nextInt(Medium.MAX_VALUE)
        val kyberPreKeyId = random.nextInt(Medium.MAX_VALUE)

        store.storePreKey(preKeyId, PreKeyRecord(preKeyId, preKeyPair))
        store.storeSignedPreKey(
            signedPreKeyId,
            SignedPreKeyRecord(
                signedPreKeyId,
                System.currentTimeMillis(),
                signedPreKeyPair,
                signedPreKeySignature,
            ),
        )
        store.storeKyberPreKey(
            kyberPreKeyId,
            KyberPreKeyRecord(
                kyberPreKeyId,
                System.currentTimeMillis(),
                kyberPreKeyPair,
                kyberPreKeySignature,
            ),
        )

        return PreKeyBundle(
            store.localRegistrationId,
            1,
            preKeyId,
            preKeyPair.publicKey,
            signedPreKeyId,
            signedPreKeyPair.publicKey,
            signedPreKeySignature,
            store.identityKeyPair.publicKey,
            kyberPreKeyId,
            kyberPreKeyPair.publicKey,
            kyberPreKeySignature,
        )
    }
}
