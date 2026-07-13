import Foundation
import PushKit

/// Registers for PushKit VoIP pushes and turns them into CallKit reports.
///
/// Every VoIP push MUST synchronously produce a `reportNewIncomingCall`
/// before its completion handler runs — iOS terminates apps that break this
/// rule and eventually stops delivering their VoIP pushes. Pushes we cannot
/// honor (bad payload, duplicate, busy) are therefore reported and then
/// immediately ended instead of being ignored.
final class VoipPushCoordinator: NSObject {
  static let shared = VoipPushCoordinator()

  private static let dedupeWindow: TimeInterval = 120
  private static let legacyTokenDefaultsKey = "expo-callkit.pushkit-token.v1"
  private static let tokenDefaultsKey = "expo-callkit.pushkit-token.v2"

  private let lock = NSLock()
  private let apsEnvironment: APNSEnvironment?
  private var registry: PKPushRegistry?
  private var tokenValue: String?
  private var seenEventIds: [String: Date] = [:]

  private override init() {
    let defaults = UserDefaults.standard
    let environment = Self.currentApsEnvironment()
    let persistedValue = defaults.object(forKey: Self.tokenDefaultsKey)
    let restoration = PushTokenPersistence.restore(
      data: persistedValue as? Data,
      currentEnvironment: environment
    )
    apsEnvironment = environment
    tokenValue = restoration.token

    // v1 stored a bare token, so it could restore a development token into a
    // production build (or the reverse). It is intentionally not migrated.
    defaults.removeObject(forKey: Self.legacyTokenDefaultsKey)
    if (persistedValue != nil && !(persistedValue is Data))
      || restoration.shouldRemovePersistedValue
    {
      defaults.removeObject(forKey: Self.tokenDefaultsKey)
    }
    super.init()
  }

  /// The current VoIP token as lowercase hex, or nil before registration.
  var currentToken: String? {
    lock.lock()
    defer { lock.unlock() }
    return tokenValue
  }

  /// Creates the PushKit registry. Idempotent; called both from the app
  /// delegate subscriber at launch (killed-state safety) and from JS.
  func register() {
    lock.lock()
    defer { lock.unlock() }
    guard registry == nil else {
      return
    }
    let pushRegistry = PKPushRegistry(queue: .main)
    pushRegistry.delegate = self
    pushRegistry.desiredPushTypes = [.voIP]
    registry = pushRegistry
  }

  // MARK: - Internals

  fileprivate func updateToken(_ newValue: String?) {
    lock.lock()
    let changed = tokenValue != newValue
    tokenValue = newValue
    lock.unlock()

    guard changed else {
      return
    }
    if let newValue, let apsEnvironment,
      let data = PushTokenPersistence.encode(token: newValue, environment: apsEnvironment)
    {
      UserDefaults.standard.set(data, forKey: Self.tokenDefaultsKey)
    } else {
      UserDefaults.standard.removeObject(forKey: Self.tokenDefaultsKey)
    }
    EventHub.shared.emit(CKEvent.voipTokenUpdated, [
      "token": newValue ?? NSNull(),
      "type": "apns-voip",
    ])
  }

  /// Development/ad-hoc builds carry the signed provisioning profile. Apple
  /// strips it from App Store/TestFlight installs, whose APNs environment is
  /// production. If a present profile cannot be read, fail closed instead of
  /// guessing and restoring a token from the wrong environment.
  private static func currentApsEnvironment() -> APNSEnvironment? {
    #if targetEnvironment(simulator)
    return nil
    #else
    guard let profileUrl = Bundle.main.url(
      forResource: "embedded",
      withExtension: "mobileprovision"
    ) else {
      return .production
    }
    guard let profileData = try? Data(contentsOf: profileUrl) else {
      return nil
    }
    return APNSEnvironment.fromProvisioningProfile(profileData)
    #endif
  }

  /// Records `eventId`, returning true when it was already seen within the
  /// dedupe window (re-delivered push).
  fileprivate func isDuplicate(eventId: String) -> Bool {
    let now = Date()
    lock.lock()
    defer { lock.unlock() }
    seenEventIds = seenEventIds.filter {
      now.timeIntervalSince($0.value) < Self.dedupeWindow
    }
    if seenEventIds[eventId] != nil {
      return true
    }
    seenEventIds[eventId] = now
    return false
  }
}

extension VoipPushCoordinator: PKPushRegistryDelegate {
  public func pushRegistry(
    _ registry: PKPushRegistry,
    didUpdate pushCredentials: PKPushCredentials,
    for type: PKPushType
  ) {
    guard type == .voIP else {
      return
    }
    let hex = pushCredentials.token.map { String(format: "%02x", $0) }.joined()
    updateToken(hex)
  }

  public func pushRegistry(
    _ registry: PKPushRegistry,
    didInvalidatePushTokenFor type: PKPushType
  ) {
    guard type == .voIP else {
      return
    }
    updateToken(nil)
  }

  public func pushRegistry(
    _ registry: PKPushRegistry,
    didReceiveIncomingPushWith payload: PKPushPayload,
    for type: PKPushType,
    completion: @escaping () -> Void
  ) {
    guard type == .voIP else {
      completion()
      return
    }

    guard let ring = RingPayload.fromPushEnvelope(payload.dictionaryPayload) else {
      NSLog("[ExpoCallKit] VoIP push payload could not be normalized")
      CallCenter.shared.reportDiscardedPush(
        callerName: nil,
        reason: .failed,
        completion: completion
      )
      return
    }

    // Some providers deliver a terminal VoIP push after the caller hangs up
    // or the dial times out. It is not a second incoming call. Close a stale
    // deterministic ring if one still exists, then perform the report-and-end
    // watchdog required for every PushKit delivery.
    if ring.isTerminalPush {
      CallCenter.shared.handleTerminalPush(
        callId: ring.callId,
        callerName: ring.caller.displayName,
        completion: completion
      )
      return
    }

    guard !isDuplicate(eventId: ring.eventId) else {
      NSLog("[ExpoCallKit] Duplicate VoIP push dropped")
      CallCenter.shared.reportDiscardedPush(
        callerName: ring.caller.displayName,
        reason: .failed,
        completion: completion
      )
      return
    }

    CallCenter.shared.reportIncomingCallFromPush(ring, completion: completion)
  }
}
