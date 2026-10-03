import Foundation
import CryptoKit
import Shared

final class CryptoKitAesGcm: IosAesGcmBackend {
    func encrypt(key: KotlinByteArray, nonce: KotlinByteArray, plaintext: KotlinByteArray) -> KotlinByteArray? {
        do {
            let sealed = try AES.GCM.seal(
                plaintext.toData(),
                using: SymmetricKey(data: key.toData()),
                nonce: AES.GCM.Nonce(data: nonce.toData()),
            )
            return (sealed.ciphertext + sealed.tag).toKotlinByteArray()
        } catch {
            NSLog("AES-GCM encrypt failed: \(error.localizedDescription)")
            return nil
        }
    }

    func decrypt(key: KotlinByteArray, nonce: KotlinByteArray, ciphertextAndTag: KotlinByteArray) -> KotlinByteArray? {
        let combined = ciphertextAndTag.toData()
        guard combined.count >= 16 else { return nil }
        let tag = combined.suffix(16)
        let ciphertext = combined.prefix(combined.count - 16)
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonce.toData()),
                ciphertext: Data(ciphertext),
                tag: Data(tag),
            )
            let opened = try AES.GCM.open(box, using: SymmetricKey(data: key.toData()))
            return opened.toKotlinByteArray()
        } catch {
            return nil
        }
    }
}

extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        let result = KotlinByteArray(size: Int32(count))
        enumerated().forEach { index, byte in
            result.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return result
    }
}

extension KotlinByteArray {
    func toData() -> Data {
        let n = Int(size)
        var bytes = [UInt8](repeating: 0, count: n)
        for i in 0..<n {
            bytes[i] = UInt8(bitPattern: get(index: Int32(i)))
        }
        return Data(bytes)
    }
}
