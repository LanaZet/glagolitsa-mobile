import Foundation
import CallKit
import PushKit
import Shared

/// Phase 4: VoIP + CallKit bridge. Registers PushKit token and reports incoming calls.
/// Until LiveKit media is wired, accept/reject still go through shared call controller paths.
final class CallKitBridge: NSObject, PKPushRegistryDelegate, CXProviderDelegate {
    static let shared = CallKitBridge()

    private let provider: CXProvider
    private var registry: PKPushRegistry?

    private override init() {
        let config = CXProviderConfiguration(localizedName: "Глаголица")
        config.supportsVideo = false
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.generic]
        provider = CXProvider(configuration: config)
        super.init()
        provider.setDelegate(self, queue: nil)
    }

    func start() {
        let reg = PKPushRegistry(queue: DispatchQueue.main)
        reg.delegate = self
        reg.desiredPushTypes = [.voIP]
        registry = reg
    }

    // MARK: - PushKit

    func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        guard type == .voIP else { return }
        let hex = pushCredentials.token.map { String(format: "%02.2hhx", $0) }.joined()
        // Store alongside APNs token channel for server registration (platform=ios, type voip later).
        UserDefaults.standard.set(hex, forKey: "glagolitsa.voip_push_token")
        NSLog("VoIP push token updated (%d bytes)", pushCredentials.token.count)
    }

    func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {
        defer { completion() }
        guard type == .voIP else { return }
        let callId = (payload.dictionaryPayload["call_id"] as? String)
            ?? (payload.dictionaryPayload["callId"] as? String)
            ?? UUID().uuidString
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: "Glagolitsa call")
        update.localizedCallerName = "Входящий звонок"
        update.hasVideo = false
        provider.reportNewIncomingCall(with: UUID(uuidString: callId) ?? UUID(), update: update) { error in
            if let error {
                NSLog("CallKit report failed: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - CXProviderDelegate

    func providerDidReset(_ provider: CXProvider) {}

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        action.fulfill()
    }
}
