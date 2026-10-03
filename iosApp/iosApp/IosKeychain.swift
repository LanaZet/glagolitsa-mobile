import Foundation
import Security
import Shared

final class SwiftSessionKeychain: IosSessionKeychainBackend {
    private let service = "com.glagolitsa.session"

    func write(account: String, value: String) -> Int32 {
        guard let data = value.data(using: .utf8) else { return errSecParam }
        let status = KeychainStore.write(service: service, account: account, value: data)
        if status != errSecSuccess {
            NSLog("Glagolitsa Keychain write status=%d account=%@", status, account)
        }
        return status
    }

    func read(account: String) -> String? {
        guard let data = KeychainStore.read(service: service, account: account) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func remove(account: String) {
        KeychainStore.remove(service: service, account: account)
    }
}

enum KeychainStore {
    static func write(service: String, account: String, value: Data) -> OSStatus {
        let lookup = lookupQuery(service: service, account: account)
        let update: [String: Any] = [
            kSecValueData as String: value,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let updateStatus = SecItemUpdate(lookup as CFDictionary, update as CFDictionary)
        if updateStatus == errSecSuccess {
            return updateStatus
        }
        guard updateStatus == errSecItemNotFound else {
            return updateStatus
        }

        var insert = lookup
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        insert[kSecValueData as String] = value
        return SecItemAdd(insert as CFDictionary, nil)
    }

    static func read(service: String, account: String) -> Data? {
        var query = lookupQuery(service: service, account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else { return nil }
        return result as? Data
    }

    static func remove(service: String, account: String) {
        SecItemDelete(lookupQuery(service: service, account: account) as CFDictionary)
    }

    private static func lookupQuery(service: String, account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
