import AVFoundation
import Foundation

/// Owns AVAudioSession configuration for calls.
///
/// This module deliberately has no WebRTC dependency: it configures a plain
/// `AVAudioSession` and signals activation strictly through events. The app's
/// media layer (e.g. the ACS calling SDK) must not start audio I/O until
/// `onAudioSessionActivated` and must stop on `onAudioSessionDeactivated` —
/// CallKit owns the actual activation.
final class AudioSessionCoordinator: NSObject {
  static let shared = AudioSessionCoordinator()

  private static let speakerRouteId = "ios:speaker"
  private static let inputRoutePrefix = "ios:input:"

  private let lifecycleLock = NSRecursiveLock()
  private let ownership = AudioRouteOwnership()

  private override init() {
    super.init()
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(routeDidChange),
      name: AVAudioSession.routeChangeNotification,
      object: AVAudioSession.sharedInstance()
    )
  }

  /// Pre-heats the session with call-appropriate settings. Safe to call
  /// repeatedly; invoked before every incoming report / outgoing start and
  /// exposed to JS as `configureAudioSession()`.
  func prewarm() {
    let session = AVAudioSession.sharedInstance()
    do {
      try session.setCategory(
        .playAndRecord,
        mode: .voiceChat,
        options: [.allowBluetooth, .allowBluetoothA2DP]
      )
    } catch {
      NSLog("[ExpoCallKit] Failed to configure AVAudioSession: \(error.localizedDescription)")
    }
  }

  /// CallKit activated the audio session — the app may start audio I/O now.
  func systemActivated() {
    lifecycleLock.lock()
    defer { lifecycleLock.unlock() }
    ownership.systemActivated(for: CallCenter.shared.firstCall()?.id)
    EventHub.shared.emit(CKEvent.audioSessionActivated)
    emitRouteChanged()
  }

  /// CallKit deactivated the audio session — the app must stop audio I/O.
  func systemDeactivated() {
    lifecycleLock.lock()
    defer { lifecycleLock.unlock() }
    let previous = ownership.systemDeactivated()
    if previous.callId != nil {
      EventHub.shared.emit(CKEvent.audioSessionDeactivated)
    }
    emitRouteChanged()
  }

  /// A connected call claims an already-active global CallKit session. This is
  /// needed when CallKit keeps audio active across a rapid end/start sequence
  /// and therefore does not send a second didActivate callback.
  func callConnected(_ callId: UUID) {
    lifecycleLock.lock()
    defer { lifecycleLock.unlock() }
    guard ownership.bindIfSystemActive(callId) else {
      return
    }
    EventHub.shared.emit(CKEvent.audioSessionActivated)
    emitRouteChanged()
  }

  /// Invalidates ownership before CallCenter releases the single-call slot.
  /// The ownership lock waits for any in-flight mutation, closing the stale UI
  /// request window before another call can become current.
  func callEnded(_ callId: UUID) {
    lifecycleLock.lock()
    defer { lifecycleLock.unlock() }
    guard ownership.invalidate(callId) else {
      return
    }
    EventHub.shared.emit(CKEvent.audioSessionDeactivated)
    emitRouteChanged()
  }

  /// Snapshot only. This never activates, changes the category, or otherwise
  /// takes ownership away from CallKit.
  func routeState() -> [String: Any] {
    let callId = CallCenter.shared.firstCall()?.id
    let state = ownership.snapshot()

    guard let callId else {
      return Self.emptyRouteState(callId: nil)
    }

    guard state.owns(callId) else {
      return Self.emptyRouteState(callId: callId)
    }

    let session = AVAudioSession.sharedInstance()
    let available = (session.availableInputs ?? []).map(Self.inputRoute)
      + [Self.speakerRoute()]

    let current = Self.currentRoute(session: session)

    return [
      "callId": callId.uuidString.lowercased(),
      "isAudioActive": true,
      "currentRoute": current ?? NSNull(),
      "availableRoutes": Self.deduplicated(available),
      "supportsRouteSelection": !available.isEmpty,
      "supportsSpeakerOverride": true,
    ]
  }

  /// On iOS, non-speaker route ids represent entries from `availableInputs`.
  /// Selecting one follows Apple's preferred-input routing model.
  func selectRoute(callId: UUID, routeId: String) throws {
    guard CallCenter.shared.call(withId: callId) != nil else {
      throw NoSuchCallException(callId.uuidString.lowercased())
    }

    if routeId == Self.speakerRouteId {
      guard try ownership.withOwnedCall(callId, {
        do {
          try AVAudioSession.sharedInstance().overrideOutputAudioPort(.speaker)
        } catch {
          throw AudioRouteRejectedException(error.localizedDescription)
        }
        return true
      }) == true else {
        throw AudioSessionInactiveException()
      }
      emitRouteChanged()
      return
    }

    guard try ownership.withOwnedCall(callId, {
      let session = AVAudioSession.sharedInstance()
      guard routeId.hasPrefix(Self.inputRoutePrefix),
            let input = session.availableInputs?.first(where: {
              Self.inputRouteId($0) == routeId
            })
      else {
        throw AudioRouteUnavailableException(routeId)
      }

      do {
        // Apple requires the exact object returned by availableInputs.
        try session.setPreferredInput(input)
        try session.overrideOutputAudioPort(.none)
      } catch {
        throw AudioRouteRejectedException(error.localizedDescription)
      }
      return true
    }) == true else {
      throw AudioSessionInactiveException()
    }
    emitRouteChanged()
  }

  /// `.none` clears our temporary speaker override; iOS then selects its
  /// normal route (which may be a headset rather than the receiver).
  func setSpeakerEnabled(callId: UUID, enabled: Bool) throws {
    guard CallCenter.shared.call(withId: callId) != nil else {
      throw NoSuchCallException(callId.uuidString.lowercased())
    }
    guard try ownership.withOwnedCall(callId, {
      do {
        try AVAudioSession.sharedInstance().overrideOutputAudioPort(enabled ? .speaker : .none)
      } catch {
        throw AudioRouteRejectedException(error.localizedDescription)
      }
      return true
    }) == true else {
      throw AudioSessionInactiveException()
    }
    emitRouteChanged()
  }

  @objc private func routeDidChange(_ notification: Notification) {
    guard ownership.snapshot().callId != nil else {
      return
    }
    emitRouteChanged()
  }

  private func emitRouteChanged() {
    EventHub.shared.emit(CKEvent.audioRouteChanged, routeState())
  }

  private static func emptyRouteState(callId: UUID?) -> [String: Any] {
    [
      "callId": callId?.uuidString.lowercased() ?? NSNull(),
      "isAudioActive": false,
      "currentRoute": NSNull(),
      "availableRoutes": [],
      "supportsRouteSelection": false,
      "supportsSpeakerOverride": false,
    ]
  }

  private static func inputRouteId(_ port: AVAudioSessionPortDescription) -> String {
    "\(inputRoutePrefix)\(port.uid)"
  }

  private static func inputRoute(_ port: AVAudioSessionPortDescription) -> [String: Any] {
    [
      "id": inputRouteId(port),
      "name": port.portName,
      "type": routeType(port.portType),
    ]
  }

  private static func outputRoute(_ port: AVAudioSessionPortDescription) -> [String: Any] {
    [
      // Output-only ids describe current state but are intentionally absent
      // from availableRoutes because AVAudioSession cannot select them.
      "id": "ios:output:\(port.uid)",
      "name": port.portName,
      "type": routeType(port.portType),
    ]
  }

  /// The output tells the UI where the caller is audible. Reuse a selectable
  /// input id only when it clearly represents the same route; otherwise keep
  /// an output-only id so the snapshot stays accurate rather than claiming a
  /// different selectable device is current.
  private static func currentRoute(session: AVAudioSession) -> [String: Any]? {
    if session.currentRoute.outputs.contains(where: { $0.portType == .builtInSpeaker }) {
      return speakerRoute()
    }

    if let output = session.currentRoute.outputs.first {
      let inputs = session.availableInputs ?? []
      let matchingInput = inputs.first(where: { $0.uid == output.uid })
        ?? inputs.first(where: {
          $0.portName == output.portName && routeType($0.portType) == routeType(output.portType)
        })
        ?? inputs.filter {
          routeType($0.portType) == routeType(output.portType)
        }.only

      return matchingInput.map(inputRoute) ?? outputRoute(output)
    }

    return session.currentRoute.inputs.first.map(inputRoute)
  }

  private static func speakerRoute() -> [String: Any] {
    ["id": speakerRouteId, "name": "Speaker", "type": "speaker"]
  }

  private static func routeType(_ port: AVAudioSession.Port) -> String {
    switch port {
    case .builtInMic, .builtInReceiver:
      return "earpiece"
    case .builtInSpeaker:
      return "speaker"
    case .bluetoothHFP, .bluetoothA2DP, .bluetoothLE:
      return "bluetooth"
    case .headsetMic, .headphones, .lineIn, .lineOut, .usbAudio:
      return "wiredHeadset"
    case .carAudio:
      return "carAudio"
    case .airPlay:
      return "streaming"
    default:
      return "unknown"
    }
  }

  private static func deduplicated(_ routes: [[String: Any]]) -> [[String: Any]] {
    var seen = Set<String>()
    return routes.filter { route in
      guard let id = route["id"] as? String else {
        return false
      }
      return seen.insert(id).inserted
    }
  }
}

private extension Array {
  var only: Element? {
    count == 1 ? first : nil
  }
}
