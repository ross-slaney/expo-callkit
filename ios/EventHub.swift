import ExpoModulesCore
import Foundation

/// Event name constants shared with `src/events.ts` and the Android side.
enum CKEvent {
  static let incomingCall = "onIncomingCall"
  static let callAnswered = "onCallAnswered"
  static let callEnded = "onCallEnded"
  static let outgoingCallStarted = "onOutgoingCallStarted"
  static let muteChanged = "onMuteChanged"
  static let holdChanged = "onHoldChanged"
  static let dtmf = "onDtmf"
  static let audioSessionActivated = "onAudioSessionActivated"
  static let audioSessionDeactivated = "onAudioSessionDeactivated"
  static let voipTokenUpdated = "onVoipTokenUpdated"

  static let all: [String] = [
    incomingCall, callAnswered, callEnded, outgoingCallStarted,
    muteChanged, holdChanged, dtmf,
    audioSessionActivated, audioSessionDeactivated, voipTokenUpdated,
  ]
}

/// Bridges native call events to JS with per-event replay buffers.
///
/// CallKit and PushKit routinely fire before the JS runtime has mounted any
/// listeners (VoIP push into a killed app, notification-driven cold starts).
/// Events whose latest occurrence matters for state reconstruction keep a
/// one-slot buffer and are replayed with `meta.flushed = true` when JS first
/// subscribes; everything else is realtime-only and dropped when unobserved.
///
/// The replay limits are fixed in `init` — synchronously, before any push can
/// possibly be processed — so there is no registration race.
final class EventHub {
  static let shared = EventHub()

  private struct Buffered {
    let body: [String: Any]
    let firedAt: Date
  }

  private let lock = NSLock()
  private weak var module: ExpoCallKitModule?
  private var observed: Set<String> = []
  private var buffers: [String: [Buffered]] = [:]
  private let replayLimits: [String: Int]

  /// Cached: `ISO8601DateFormatter` is expensive to allocate and thread-safe.
  private static let timestampFormatter: ISO8601DateFormatter = {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return formatter
  }()

  private init() {
    replayLimits = [
      CKEvent.incomingCall: 1,
      CKEvent.callAnswered: 1,
      CKEvent.callEnded: 1,
      CKEvent.voipTokenUpdated: 1,
      CKEvent.audioSessionActivated: 1,
    ]
  }

  /// Connects (or disconnects, with nil) the live Expo module instance.
  func attach(_ module: ExpoCallKitModule?) {
    lock.lock()
    self.module = module
    lock.unlock()
  }

  /// Emits an event to JS, or buffers it when nobody is listening yet.
  func emit(_ name: String, _ body: [String: Any] = [:]) {
    let firedAt = Date()
    var target: ExpoCallKitModule?

    lock.lock()
    if observed.contains(name), let liveModule = module {
      target = liveModule
    } else {
      let limit = replayLimits[name] ?? 0
      if limit > 0 {
        var buffer = buffers[name, default: []]
        buffer.append(Buffered(body: body, firedAt: firedAt))
        if buffer.count > limit {
          buffer.removeFirst(buffer.count - limit)
        }
        buffers[name] = buffer
      }
    }
    lock.unlock()

    if let target {
      target.sendEvent(name, Self.decorate(body, flushed: false, firedAt: firedAt))
    }
  }

  /// Marks an event observed and replays anything buffered for it.
  func startObserving(_ name: String) {
    lock.lock()
    observed.insert(name)
    let pending = buffers.removeValue(forKey: name) ?? []
    let target = module
    lock.unlock()

    guard let target, !pending.isEmpty else {
      return
    }
    for item in pending {
      target.sendEvent(name, Self.decorate(item.body, flushed: true, firedAt: item.firedAt))
    }
  }

  func stopObserving(_ name: String) {
    lock.lock()
    observed.remove(name)
    lock.unlock()
  }

  private static func decorate(
    _ body: [String: Any],
    flushed: Bool,
    firedAt: Date
  ) -> [String: Any] {
    var decorated = body
    decorated["meta"] = [
      "flushed": flushed,
      "timestamp": timestampFormatter.string(from: firedAt),
    ]
    return decorated
  }
}
