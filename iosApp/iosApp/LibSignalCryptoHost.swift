import Foundation
import LibSignalClient
import Shared
import Security

private let signalDeviceId: UInt32 = 1
private let oneTimePreKeyBatch = 20
private let fingerprintIterations = 5200
private let apiWhisperType: Int32 = 1
private let apiPreKeyType: Int32 = 3
private let apiSenderKeyType: Int32 = 4

final class LibSignalCryptoHost: IosSignalCryptoHost {
    private let lock = NSLock()
    private var stores: [String: PersistingSignalStore] = [:]

    func loadIdentity(accountId: String) -> DeviceIdentity? {
        lock.lock()
        defer { lock.unlock() }
        guard let stored = IdentityStore.load(accountId: accountId),
              let identity = stored.deviceIdentity(accountId: accountId),
              let store = PersistingSignalStore.load(accountId: accountId, stored: stored)
        else { return nil }
        stores[accountId] = store
        return identity
    }

    func createIdentity(accountId: String, deviceId: String) -> DeviceIdentity? {
        lock.lock()
        defer { lock.unlock() }
        let identity = IdentityKeyPair.generate()
        let registrationId = UInt32.random(in: 1...0x3FFF)
        let stored = StoredIdentity(
            deviceId: deviceId,
            registrationId: registrationId,
            identityBlob: identity.serialize(),
        )
        guard IdentityStore.save(accountId: accountId, stored: stored) else {
            NSLog("createIdentity failed: Keychain write failed")
            return nil
        }
        guard let store = PersistingSignalStore.load(accountId: accountId, stored: stored),
              let deviceIdentity = stored.deviceIdentity(accountId: accountId)
        else { return nil }
        stores[accountId] = store
        return deviceIdentity
    }

    func clearIdentity(accountId: String) {
        lock.lock()
        defer { lock.unlock() }
        stores.removeValue(forKey: accountId)
        IdentityStore.clear(accountId: accountId)
        PersistingSignalStore.clear(accountId: accountId)
    }

    func buildDeviceRegistration(accountId: String) -> RegisterDeviceRequest? {
        lock.lock()
        defer { lock.unlock() }
        guard let store = store(for: accountId) else { return nil }
        do {
            let context = NullContext()
            let identity = try store.identityKeyPair(context: context)
            let signedKey = PrivateKey.generate()
            let signedId = randomPreKeyId()
            let signedSignature = identity.privateKey.generateSignature(message: signedKey.publicKey.serialize())
            let now = UInt64(Date().timeIntervalSince1970 * 1000)
            try store.storeSignedPreKey(
                SignedPreKeyRecord(id: signedId, timestamp: now, privateKey: signedKey, signature: signedSignature),
                id: signedId,
                context: context,
            )

            let kyber = KEMKeyPair.generate()
            let kyberId = randomPreKeyId()
            let kyberSignature = identity.privateKey.generateSignature(message: kyber.publicKey.serialize())
            try store.storeKyberPreKey(
                KyberPreKeyRecord(id: kyberId, timestamp: now, keyPair: kyber, signature: kyberSignature),
                id: kyberId,
                context: context,
            )

            var oneTime: [OneTimePreKeyMaterial] = []
            for _ in 0..<oneTimePreKeyBatch {
                let preKeyId = randomPreKeyId()
                let preKey = PrivateKey.generate()
                try store.storePreKey(
                    PreKeyRecord(id: preKeyId, privateKey: preKey),
                    id: preKeyId,
                    context: context,
                )
                oneTime.append(
                    OneTimePreKeyMaterial(
                        id: Int32(preKeyId),
                        public_key: preKey.publicKey.serialize().base64EncodedString(),
                    ),
                )
            }

            return RegisterDeviceRequest(
                device_id: store.deviceId,
                registration_id: Int32(store.registrationId),
                identity_public_key: identity.publicKey.serialize().base64EncodedString(),
                signed_prekey: SignedPreKeyMaterial(
                    id: Int32(signedId),
                    public_key: signedKey.publicKey.serialize().base64EncodedString(),
                    signature: signedSignature.base64EncodedString(),
                    created_at: Int64(now),
                ),
                pq_prekey: PqPreKeyMaterial(
                    id: Int32(kyberId),
                    public_material: kyber.publicKey.serialize().base64EncodedString(),
                    signature: kyberSignature.base64EncodedString(),
                    created_at: Int64(now),
                ),
                one_time_prekeys: oneTime,
                attestation: nil,
            )
        } catch {
            NSLog("buildDeviceRegistration failed: \(error.localizedDescription)")
            return nil
        }
    }

    func ensureSession(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
        bundle: DeviceKeyBundle,
    ) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let store = store(for: accountId) else { return false }
        do {
            let context = NullContext()
            let remote = try protocolAddress(accountId: remoteAccountId, deviceId: remoteDeviceId)
            if try store.loadSession(for: remote, context: context) != nil {
                return true
            }
            let local = try protocolAddress(accountId: accountId, deviceId: store.deviceId)
            try processPreKeyBundle(
                preKeyBundle(from: bundle),
                for: remote,
                ourAddress: local,
                sessionStore: store,
                identityStore: store,
                context: context,
            )
            return true
        } catch {
            NSLog("ensureSession failed: \(error.localizedDescription)")
            return false
        }
    }

    func resetSession(accountId: String, remoteAccountId: String, remoteDeviceId: String) {
        lock.lock()
        defer { lock.unlock() }
        guard let store = store(for: accountId) else { return }
        do {
            let remote = try protocolAddress(accountId: remoteAccountId, deviceId: remoteDeviceId)
            try store.removeSessionFile(for: remote)
            if let stored = IdentityStore.load(accountId: accountId),
               let reloaded = PersistingSignalStore.load(accountId: accountId, stored: stored)
            {
                stores[accountId] = reloaded
            }
        } catch {
            NSLog("resetSession failed: \(error.localizedDescription)")
        }
    }

    func encryptMessage(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceId: String,
        plaintext: KotlinByteArray,
    ) -> EncryptedPayload? {
        lock.lock()
        defer { lock.unlock() }
        guard let store = store(for: accountId) else { return nil }
        do {
            let context = NullContext()
            let remote = try protocolAddress(accountId: remoteAccountId, deviceId: remoteDeviceId)
            guard try store.loadSession(for: remote, context: context) != nil else { return nil }
            let local = try protocolAddress(accountId: accountId, deviceId: store.deviceId)
            let encrypted = try signalEncrypt(
                message: plaintext.toData(),
                for: remote,
                localAddress: local,
                sessionStore: store,
                identityStore: store,
                context: context,
            )
            return EncryptedPayload(
                envelopeType: apiEnvelopeType(encrypted.messageType),
                ciphertext: encrypted.serialize().toKotlinByteArray(),
            )
        } catch {
            NSLog("encryptMessage failed: \(error.localizedDescription)")
            return nil
        }
    }

    func decryptMessage(
        accountId: String,
        remoteAccountId: String,
        remoteDeviceUuid: String,
        envelopeType: Int32,
        ciphertext: KotlinByteArray,
    ) -> KotlinByteArray? {
        lock.lock()
        defer { lock.unlock() }
        guard let store = store(for: accountId) else { return nil }
        do {
            let context = NullContext()
            let remote = try protocolAddress(accountId: remoteAccountId, deviceId: remoteDeviceUuid)
            let local = try protocolAddress(accountId: accountId, deviceId: store.deviceId)
            let data = ciphertext.toData()
            let plaintext: Data
            if envelopeType == apiPreKeyType {
                plaintext = try signalDecryptPreKey(
                    message: PreKeySignalMessage(bytes: data),
                    from: remote,
                    localAddress: local,
                    sessionStore: store,
                    identityStore: store,
                    preKeyStore: store,
                    signedPreKeyStore: store,
                    kyberPreKeyStore: store,
                    context: context,
                )
            } else {
                plaintext = try signalDecrypt(
                    message: SignalMessage(bytes: data),
                    from: remote,
                    to: local,
                    sessionStore: store,
                    identityStore: store,
                    context: context,
                )
            }
            return plaintext.toKotlinByteArray()
        } catch {
            NSLog("decryptMessage failed: \(error.localizedDescription)")
            return nil
        }
    }

    func buildPrekeyReplenishment(accountId: String, count: Int32) -> [OneTimePreKeyMaterial]? {
        lock.lock()
        defer { lock.unlock() }
        guard let store = store(for: accountId) else { return nil }
        do {
            let context = NullContext()
            var keys: [OneTimePreKeyMaterial] = []
            for _ in 0..<max(Int(count), 0) {
                let preKeyId = randomPreKeyId()
                let preKey = PrivateKey.generate()
                try store.storePreKey(PreKeyRecord(id: preKeyId, privateKey: preKey), id: preKeyId, context: context)
                keys.append(
                    OneTimePreKeyMaterial(
                        id: Int32(preKeyId),
                        public_key: preKey.publicKey.serialize().base64EncodedString(),
                    ),
                )
            }
            return keys
        } catch {
            return nil
        }
    }

    func buildSignedPreKeyRotation(accountId: String) -> RotateSignedPreKeyRequest? {
        lock.lock()
        defer { lock.unlock() }
        guard let store = store(for: accountId) else { return nil }
        do {
            let context = NullContext()
            let identity = try store.identityKeyPair(context: context)
            let now = UInt64(Date().timeIntervalSince1970 * 1000)
            let signedKey = PrivateKey.generate()
            let signedId = randomPreKeyId()
            let signedSignature = identity.privateKey.generateSignature(message: signedKey.publicKey.serialize())
            try store.storeSignedPreKey(
                SignedPreKeyRecord(id: signedId, timestamp: now, privateKey: signedKey, signature: signedSignature),
                id: signedId,
                context: context,
            )
            let kyber = KEMKeyPair.generate()
            let kyberId = randomPreKeyId()
            let kyberSignature = identity.privateKey.generateSignature(message: kyber.publicKey.serialize())
            try store.storeKyberPreKey(
                KyberPreKeyRecord(id: kyberId, timestamp: now, keyPair: kyber, signature: kyberSignature),
                id: kyberId,
                context: context,
            )
            return RotateSignedPreKeyRequest(
                signed_prekey: SignedPreKeyMaterial(
                    id: Int32(signedId),
                    public_key: signedKey.publicKey.serialize().base64EncodedString(),
                    signature: signedSignature.base64EncodedString(),
                    created_at: Int64(now),
                ),
                pq_prekey: PqPreKeyMaterial(
                    id: Int32(kyberId),
                    public_material: kyber.publicKey.serialize().base64EncodedString(),
                    signature: kyberSignature.base64EncodedString(),
                    created_at: Int64(now),
                ),
            )
        } catch {
            return nil
        }
    }

    func trustRemoteIdentity(accountId: String, remoteAccountId: String, remoteDeviceId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let store = store(for: accountId) else { return false }
        do {
            let remote = try protocolAddress(accountId: remoteAccountId, deviceId: remoteDeviceId)
            try store.trust(address: remote)
            return true
        } catch {
            return false
        }
    }

    func createSenderKeyDistribution(accountId: String, deviceId: String, chatId: String) -> KotlinByteArray? {
        lock.lock()
        defer { lock.unlock() }
        guard let store = store(for: accountId), let uuid = UUID(uuidString: chatId) else { return nil }
        do {
            let sender = try protocolAddress(accountId: accountId, deviceId: deviceId)
            let message = try SenderKeyDistributionMessage(
                from: sender,
                distributionId: uuid,
                store: store,
                context: NullContext(),
            )
            return message.serialize().toKotlinByteArray()
        } catch {
            NSLog("createSenderKeyDistribution failed: \(error.localizedDescription)")
            return nil
        }
    }

    func processSenderKeyDistribution(
        accountId: String,
        senderAccountId: String,
        senderDeviceId: String,
        chatId: String,
        distributionBytes: KotlinByteArray,
    ) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let store = store(for: accountId) else { return false }
        do {
            let sender = try protocolAddress(accountId: senderAccountId, deviceId: senderDeviceId)
            try processSenderKeyDistributionMessage(
                SenderKeyDistributionMessage(bytes: distributionBytes.toData()),
                from: sender,
                store: store,
                context: NullContext(),
            )
            return true
        } catch {
            NSLog("processSenderKeyDistribution failed: \(error.localizedDescription)")
            return false
        }
    }

    func encryptGroupMessage(
        accountId: String,
        deviceId: String,
        chatId: String,
        plaintext: KotlinByteArray,
    ) -> EncryptedPayload? {
        lock.lock()
        defer { lock.unlock() }
        guard let store = store(for: accountId), let uuid = UUID(uuidString: chatId) else { return nil }
        do {
            let sender = try protocolAddress(accountId: accountId, deviceId: deviceId)
            let encrypted = try groupEncrypt(
                plaintext.toData(),
                from: sender,
                distributionId: uuid,
                store: store,
                context: NullContext(),
            )
            return EncryptedPayload(
                envelopeType: apiSenderKeyType,
                ciphertext: encrypted.serialize().toKotlinByteArray(),
            )
        } catch {
            NSLog("encryptGroupMessage failed: \(error.localizedDescription)")
            return nil
        }
    }

    func decryptGroupMessage(
        accountId: String,
        senderAccountId: String,
        senderDeviceId: String,
        chatId: String,
        ciphertext: KotlinByteArray,
    ) -> KotlinByteArray? {
        lock.lock()
        defer { lock.unlock() }
        guard let store = store(for: accountId) else { return nil }
        do {
            let sender = try protocolAddress(accountId: senderAccountId, deviceId: senderDeviceId)
            let plaintext = try groupDecrypt(
                ciphertext.toData(),
                from: sender,
                store: store,
                context: NullContext(),
            )
            return plaintext.toKotlinByteArray()
        } catch {
            NSLog("decryptGroupMessage failed: \(error.localizedDescription)")
            return nil
        }
    }

    func safetyNumber(
        accountId: String,
        remoteAccountId: String,
        remoteIdentityPublicKey: KotlinByteArray,
        remoteRegistrationId: Int32,
    ) -> SafetyNumberInfo? {
        lock.lock()
        defer { lock.unlock() }
        guard let store = store(for: accountId) else { return nil }
        do {
            let local = try store.identityKeyPair(context: NullContext())
            let remoteKey = try PublicKey(remoteIdentityPublicKey.toData())
            let fingerprint = try NumericFingerprintGenerator(iterations: fingerprintIterations).create(
                version: 0,
                localIdentifier: Data(accountId.utf8),
                localKey: local.publicKey,
                remoteIdentifier: Data(remoteAccountId.utf8),
                remoteKey: remoteKey,
            )
            return SafetyNumberInfo(
                displayText: fingerprint.displayable.formatted,
                qrPayload: fingerprint.scannable.encoding.toKotlinByteArray(),
            )
        } catch {
            return nil
        }
    }

    func runSignalSelfTest() -> Bool {
        do {
            let aliceIdentity = IdentityKeyPair.generate()
            let bobIdentity = IdentityKeyPair.generate()
            let aliceStore = InMemorySignalProtocolStore(identity: aliceIdentity, registrationId: 1)
            let bobStore = InMemorySignalProtocolStore(identity: bobIdentity, registrationId: 2)
            let aliceAddress = try ProtocolAddress(name: "alice", deviceId: signalDeviceId)
            let bobAddress = try ProtocolAddress(name: "bob", deviceId: signalDeviceId)
            let context = NullContext()

            let bobSigned = PrivateKey.generate()
            let bobSignedId: UInt32 = 1
            let bobSignedSig = bobIdentity.privateKey.generateSignature(message: bobSigned.publicKey.serialize())
            let now = UInt64(Date().timeIntervalSince1970 * 1000)
            try bobStore.storeSignedPreKey(
                SignedPreKeyRecord(id: bobSignedId, timestamp: now, privateKey: bobSigned, signature: bobSignedSig),
                id: bobSignedId,
                context: context,
            )
            let bobPreKey = PrivateKey.generate()
            try bobStore.storePreKey(PreKeyRecord(id: 2, privateKey: bobPreKey), id: 2, context: context)
            let bobKyber = KEMKeyPair.generate()
            let bobKyberSig = bobIdentity.privateKey.generateSignature(message: bobKyber.publicKey.serialize())
            try bobStore.storeKyberPreKey(
                KyberPreKeyRecord(id: 3, timestamp: now, keyPair: bobKyber, signature: bobKyberSig),
                id: 3,
                context: context,
            )
            let bundle = try PreKeyBundle(
                registrationId: 2,
                deviceId: signalDeviceId,
                prekeyId: 2,
                prekey: bobPreKey.publicKey,
                signedPrekeyId: bobSignedId,
                signedPrekey: bobSigned.publicKey,
                signedPrekeySignature: bobSignedSig,
                identity: bobIdentity.identityKey,
                kyberPrekeyId: 3,
                kyberPrekey: bobKyber.publicKey,
                kyberPrekeySignature: bobKyberSig,
            )
            try processPreKeyBundle(
                bundle,
                for: bobAddress,
                ourAddress: aliceAddress,
                sessionStore: aliceStore,
                identityStore: aliceStore,
                context: context,
            )
            let ciphertext = try signalEncrypt(
                message: Data("glagolitsa-ios".utf8),
                for: bobAddress,
                localAddress: aliceAddress,
                sessionStore: aliceStore,
                identityStore: aliceStore,
                context: context,
            )
            let decrypted = try signalDecryptPreKey(
                message: PreKeySignalMessage(bytes: ciphertext.serialize()),
                from: aliceAddress,
                localAddress: bobAddress,
                sessionStore: bobStore,
                identityStore: bobStore,
                preKeyStore: bobStore,
                signedPreKeyStore: bobStore,
                kyberPreKeyStore: bobStore,
                context: context,
            )
            return String(data: decrypted, encoding: .utf8) == "glagolitsa-ios"
        } catch {
            NSLog("iOS libsignal self-test failed: \(error.localizedDescription)")
            return false
        }
    }

    private func store(for accountId: String) -> PersistingSignalStore? {
        if let existing = stores[accountId] {
            return existing
        }
        guard let stored = IdentityStore.load(accountId: accountId) else { return nil }
        guard let created = PersistingSignalStore.load(accountId: accountId, stored: stored) else { return nil }
        stores[accountId] = created
        return created
    }

    private func protocolAddress(accountId: String, deviceId: String) throws -> ProtocolAddress {
        try ProtocolAddress(name: "\(accountId)#\(deviceId)", deviceId: signalDeviceId)
    }

    private func preKeyBundle(from bundle: DeviceKeyBundle) throws -> PreKeyBundle {
        let registrationId = try positiveId(bundle.registration_id, field: "registration_id")
        let identity = IdentityKey(publicKey: try PublicKey(decodeKey(bundle.identity_public_key, field: "identity_public_key")))
        let signed = bundle.signed_prekey
        let pq = bundle.pq_prekey
        let signedId = try positiveId(signed.id, field: "signed_prekey.id")
        let signedKey = try PublicKey(decodeKey(signed.public_key, field: "signed_prekey.public_key"))
        let signedSignature = try decodeKey(signed.signature, field: "signed_prekey.signature")
        let kyberId = try positiveId(pq.id, field: "pq_prekey.id")
        let kyberKey = try KEMPublicKey(decodeKey(pq.public_material, field: "pq_prekey.public_material"))
        let kyberSignature = try decodeKey(pq.signature, field: "pq_prekey.signature")
        if let oneTime = bundle.one_time_prekey,
           oneTime.id > 0
        {
            return try PreKeyBundle(
                registrationId: registrationId,
                deviceId: signalDeviceId,
                prekeyId: try positiveId(oneTime.id, field: "one_time_prekey.id"),
                prekey: PublicKey(try decodeKey(oneTime.public_key, field: "one_time_prekey.public_key")),
                signedPrekeyId: signedId,
                signedPrekey: signedKey,
                signedPrekeySignature: signedSignature,
                identity: identity,
                kyberPrekeyId: kyberId,
                kyberPrekey: kyberKey,
                kyberPrekeySignature: kyberSignature,
            )
        }
        return try PreKeyBundle(
            registrationId: registrationId,
            deviceId: signalDeviceId,
            signedPrekeyId: signedId,
            signedPrekey: signedKey,
            signedPrekeySignature: signedSignature,
            identity: identity,
            kyberPrekeyId: kyberId,
            kyberPrekey: kyberKey,
            kyberPrekeySignature: kyberSignature,
        )
    }

    private func positiveId(_ value: Int32, field: String) throws -> UInt32 {
        guard value > 0 else { throw SignalHostError.invalidField(field) }
        return UInt32(value)
    }

    private func decodeKey(_ value: String, field: String) throws -> Data {
        guard let data = Data(base64Encoded: value), !data.isEmpty else {
            throw SignalHostError.invalidField(field)
        }
        return data
    }

    private func apiEnvelopeType(_ type: CiphertextMessage.MessageType) -> Int32 {
        if type == .whisper { return apiWhisperType }
        if type == .preKey { return apiPreKeyType }
        if type == .senderKey { return apiSenderKeyType }
        return Int32(type.rawValue)
    }

    private func randomPreKeyId() -> UInt32 {
        UInt32.random(in: 1...0xFFFFFF)
    }
}

private enum SignalHostError: LocalizedError {
    case invalidField(String)

    var errorDescription: String? {
        switch self {
        case .invalidField(let field): return "Invalid Signal bundle field: \(field)"
        }
    }
}

private struct StoredIdentity {
    let deviceId: String
    let registrationId: UInt32
    let identityBlob: Data

    func deviceIdentity(accountId: String) -> DeviceIdentity? {
        guard let pair = try? IdentityKeyPair(bytes: identityBlob) else { return nil }
        return DeviceIdentity(
            accountId: accountId,
            deviceId: deviceId,
            registrationId: Int32(registrationId),
            identityPublicKey: pair.publicKey.serialize().toKotlinByteArray(),
        )
    }
}

private enum IdentityStore {
    private static let service = "com.glagolitsa.signal.identity"

    static func load(accountId: String) -> StoredIdentity? {
        guard let data = KeychainStore.read(service: service, account: accountId),
              let raw = String(data: data, encoding: .utf8)
        else { return nil }
        let parts = raw.split(separator: "|", omittingEmptySubsequences: false)
        guard parts.count == 3,
              let registrationId = UInt32(parts[1]),
              let blob = Data(base64Encoded: String(parts[2]))
        else { return nil }
        return StoredIdentity(deviceId: String(parts[0]), registrationId: registrationId, identityBlob: blob)
    }

    static func save(accountId: String, stored: StoredIdentity) -> Bool {
        let raw = "\(stored.deviceId)|\(stored.registrationId)|\(stored.identityBlob.base64EncodedString())"
        return KeychainStore.write(service: service, account: accountId, value: Data(raw.utf8)) == errSecSuccess
    }

    static func clear(accountId: String) {
        KeychainStore.remove(service: service, account: accountId)
    }
}

private final class PersistingSignalStore: InMemorySignalProtocolStore {
    let accountId: String
    let deviceId: String
    let registrationId: UInt32
    private var trusted = Set<String>()
    private let root: URL

    static func load(accountId: String, stored: StoredIdentity) -> PersistingSignalStore? {
        guard let identity = try? IdentityKeyPair(bytes: stored.identityBlob) else { return nil }
        let store = PersistingSignalStore(
            identity: identity,
            registrationId: stored.registrationId,
            accountId: accountId,
            deviceId: stored.deviceId,
        )
        store.loadFromDisk()
        return store
    }

    static func clear(accountId: String) {
        let root = directory(for: accountId)
        try? FileManager.default.removeItem(at: root)
    }

    init(identity: IdentityKeyPair, registrationId: UInt32, accountId: String, deviceId: String) {
        self.accountId = accountId
        self.deviceId = deviceId
        self.registrationId = registrationId
        self.root = Self.directory(for: accountId)
        super.init(identity: identity, registrationId: registrationId)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    func trust(address: ProtocolAddress) throws {
        trusted.insert(addressKey(address))
        try write("trusted_\(addressKey(address))", Data("1".utf8))
    }

    func removeSessionFile(for address: ProtocolAddress) throws {
        try remove("session_\(addressKey(address))")
    }

    override func isTrustedIdentity(
        _ identity: IdentityKey,
        for address: ProtocolAddress,
        direction: Direction,
        context: StoreContext
    ) throws -> Bool {
        if trusted.contains(addressKey(address)) { return true }
        return try super.isTrustedIdentity(identity, for: address, direction: direction, context: context)
    }

    override func saveIdentity(_ identity: IdentityKey, for address: ProtocolAddress, context: StoreContext) throws -> IdentityChange {
        let change = try super.saveIdentity(identity, for: address, context: context)
        try write("identity_\(addressKey(address))", identity.serialize())
        return change
    }

    override func storePreKey(_ record: PreKeyRecord, id: UInt32, context: StoreContext) throws {
        try super.storePreKey(record, id: id, context: context)
        try write("prekey_\(id)", record.serialize())
    }

    override func storeSignedPreKey(_ record: SignedPreKeyRecord, id: UInt32, context: StoreContext) throws {
        try super.storeSignedPreKey(record, id: id, context: context)
        try write("signed_prekey_\(id)", record.serialize())
    }

    override func storeKyberPreKey(_ record: KyberPreKeyRecord, id: UInt32, context: StoreContext) throws {
        try super.storeKyberPreKey(record, id: id, context: context)
        try write("kyber_prekey_\(id)", record.serialize())
    }

    override func storeSession(_ record: SessionRecord, for address: ProtocolAddress, context: StoreContext) throws {
        try super.storeSession(record, for: address, context: context)
        try write("session_\(addressKey(address))", record.serialize())
    }

    override func storeSenderKey(
        from sender: ProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord,
        context: StoreContext
    ) throws {
        try super.storeSenderKey(from: sender, distributionId: distributionId, record: record, context: context)
        try write("senderkey_\(addressKey(sender))_\(distributionId.uuidString)", record.serialize())
    }

    private func loadFromDisk() {
        let context = NullContext()
        guard let files = try? FileManager.default.contentsOfDirectory(atPath: root.path) else { return }
        for name in files {
            guard let data = try? Data(contentsOf: root.appendingPathComponent(name)) else { continue }
            if name.hasPrefix("trusted_") {
                trusted.insert(String(name.dropFirst("trusted_".count)))
            } else if name.hasPrefix("session_"), let address = parseAddress(String(name.dropFirst("session_".count))) {
                if let record = try? SessionRecord(bytes: data) {
                    try? super.storeSession(record, for: address, context: context)
                }
            } else if name.hasPrefix("prekey_"), let id = UInt32(name.dropFirst("prekey_".count)) {
                if let record = try? PreKeyRecord(bytes: data) {
                    try? super.storePreKey(record, id: id, context: context)
                }
            } else if name.hasPrefix("signed_prekey_"), let id = UInt32(name.dropFirst("signed_prekey_".count)) {
                if let record = try? SignedPreKeyRecord(bytes: data) {
                    try? super.storeSignedPreKey(record, id: id, context: context)
                }
            } else if name.hasPrefix("kyber_prekey_"), let id = UInt32(name.dropFirst("kyber_prekey_".count)) {
                if let record = try? KyberPreKeyRecord(bytes: data) {
                    try? super.storeKyberPreKey(record, id: id, context: context)
                }
            } else if name.hasPrefix("identity_"), let address = parseAddress(String(name.dropFirst("identity_".count))) {
                if let key = try? IdentityKey(bytes: data) {
                    _ = try? super.saveIdentity(key, for: address, context: context)
                }
            } else if name.hasPrefix("senderkey_") {
                let rest = String(name.dropFirst("senderkey_".count))
                if let split = rest.lastIndex(of: "_") {
                    let addressPart = String(rest[..<split])
                    let uuidPart = String(rest[rest.index(after: split)...])
                    if let address = parseAddress(addressPart), let uuid = UUID(uuidString: uuidPart),
                       let record = try? SenderKeyRecord(bytes: data)
                    {
                        try? super.storeSenderKey(from: address, distributionId: uuid, record: record, context: context)
                    }
                }
            }
        }
    }

    private func write(_ name: String, _ data: Data) throws {
        try data.write(
            to: root.appendingPathComponent(name),
            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
        )
    }

    private func remove(_ name: String) throws {
        let url = root.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        try FileManager.default.removeItem(at: url)
    }

    private func addressKey(_ address: ProtocolAddress) -> String {
        "\(Self.fileSafe(address.name)).\(address.deviceId)"
    }

    private func parseAddress(_ raw: String) -> ProtocolAddress? {
        guard let split = raw.lastIndex(of: ".") else { return nil }
        let encodedName = String(raw[..<split])
        guard let name = Self.decodeFileSafe(encodedName) else { return nil }
        guard let device = UInt32(raw[raw.index(after: split)...]) else { return nil }
        return try? ProtocolAddress(name: name, deviceId: device)
    }

    private static func directory(for accountId: String) -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let safe = fileSafe(accountId)
        return base.appendingPathComponent("glagolitsa-signal/\(safe)", isDirectory: true)
    }

    private static func fileSafe(_ value: String) -> String {
        Data(value.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func decodeFileSafe(_ value: String) -> String? {
        var base64 = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
        guard let data = Data(base64Encoded: base64) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
