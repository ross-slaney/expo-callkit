import CallKit
import Foundation

// MARK: - Configuration (Info.plist)

/// Reads module configuration written by the config plugin.
enum CallKitSetup {
  static let ringtoneKey = "ExpoCallKitRingtone"
  static let incomingTimeoutKey = "ExpoCallKitIncomingTimeout"
  static let answerFulfillTimeoutKey = "ExpoCallKitAnswerFulfillTimeout"
  static let outgoingTimeoutKey = "ExpoCallKitOutgoingTimeout"

  static var ringtone: String? {
    guard let value = Bundle.main.object(forInfoDictionaryKey: ringtoneKey) as? String,
      !value.isEmpty, value != "default"
    else {
      return nil
    }
    return value
  }

  static var incomingTimeout: TimeInterval {
    timeout(forKey: incomingTimeoutKey, default: 45)
  }

  static var answerFulfillTimeout: TimeInterval {
    timeout(forKey: answerFulfillTimeoutKey, default: 30)
  }

  static var outgoingTimeout: TimeInterval {
    timeout(forKey: outgoingTimeoutKey, default: 60)
  }

  private static func timeout(forKey key: String, default fallback: TimeInterval) -> TimeInterval {
    guard let value = Bundle.main.object(forInfoDictionaryKey: key) as? NSNumber,
      value.doubleValue > 0
    else {
      return fallback
    }
    return value.doubleValue
  }
}

// MARK: - Participants

struct Participant {
  let id: String
  let displayName: String?
  let phoneNumber: String?
  let email: String?

  func asDictionary() -> [String: Any] {
    var dict: [String: Any] = ["id": id]
    if let displayName { dict["displayName"] = displayName }
    if let phoneNumber { dict["phoneNumber"] = phoneNumber }
    if let email { dict["email"] = email }
    return dict
  }

  /// Builds the CallKit handle. Phone numbers get first-class treatment;
  /// everything else falls back to a generic handle keyed by participant id
  /// (matching `supportedHandleTypes = [.phoneNumber, .generic]`).
  func asHandle() -> CXHandle {
    if let phoneNumber, !phoneNumber.isEmpty {
      return CXHandle(type: .phoneNumber, value: phoneNumber)
    }
    return CXHandle(type: .generic, value: id)
  }
}

// MARK: - Call state

enum CallOrigin: String {
  case incoming
  case outgoing
}

enum CallStatus: String {
  case ringing
  case connecting
  case connected
  case ended
}

enum EndReason: String {
  case failed
  case remoteEnded
  case unanswered
  case answeredElsewhere
  case declinedElsewhere
  case local
  case unknown

  /// The CallKit reason used with `provider.reportCall(with:endedAt:reason:)`.
  /// `local` and `unknown` have no CX equivalent and degrade to `.failed`;
  /// local ends normally go through `CXEndCallAction` instead.
  var cxReason: CXCallEndedReason {
    switch self {
    case .remoteEnded: return .remoteEnded
    case .unanswered: return .unanswered
    case .answeredElsewhere: return .answeredElsewhere
    case .declinedElsewhere: return .declinedElsewhere
    case .failed, .local, .unknown: return .failed
    }
  }
}

/// In-memory state for one call session.
struct ActiveCall {
  let id: UUID
  let origin: CallOrigin
  var status: CallStatus
  let remoteParty: Participant
  let serverCallId: String?
  let metadata: [String: Any]?
  let hasVideo: Bool
  var isMuted: Bool = false
  var isOnHold: Bool = false
  var connectedAt: Date?

  private static let timestampFormatter: ISO8601DateFormatter = {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return formatter
  }()

  /// Serializes to the TS `CallSession` shape.
  func asSessionDictionary() -> [String: Any] {
    var dict: [String: Any] = [
      "id": id.uuidString.lowercased(),
      "origin": origin.rawValue,
      "status": status.rawValue,
      "isMuted": isMuted,
      "isOnHold": isOnHold,
    ]
    switch origin {
    case .incoming:
      dict["caller"] = remoteParty.asDictionary()
    case .outgoing:
      dict["recipient"] = remoteParty.asDictionary()
    }
    if let serverCallId { dict["serverCallId"] = serverCallId }
    if let metadata { dict["metadata"] = metadata }
    if let connectedAt {
      dict["connectedAt"] = Self.timestampFormatter.string(from: connectedAt)
    }
    return dict
  }
}

// MARK: - Incoming call payload

/// Validated description of an incoming call, from a VoIP push or from JS.
struct RingPayload {
  let eventId: String
  let serverCallId: String
  let caller: Participant
  let hasVideo: Bool
  let metadata: [String: Any]?

  func asDictionary() -> [String: Any] {
    var dict: [String: Any] = [
      "eventId": eventId,
      "serverCallId": serverCallId,
      "caller": caller.asDictionary(),
      "hasVideo": hasVideo,
    ]
    if let metadata { dict["metadata"] = metadata }
    return dict
  }

  /// Extracts a payload from a VoIP push envelope. The push body must carry
  /// the call description under the top-level key `"incomingCall"`.
  static func fromPushEnvelope(_ envelope: [AnyHashable: Any]) -> RingPayload? {
    guard let inner = envelope["incomingCall"] as? [AnyHashable: Any] else {
      return nil
    }
    return fromFields(inner)
  }

  /// Validates raw fields. Requires non-empty `eventId`, `serverCallId`, and
  /// `caller.id`; everything else is optional.
  static func fromFields(_ fields: [AnyHashable: Any]) -> RingPayload? {
    guard
      let eventId = nonEmptyString(fields["eventId"]),
      let serverCallId = nonEmptyString(fields["serverCallId"]),
      let callerFields = fields["caller"] as? [AnyHashable: Any],
      let callerId = nonEmptyString(callerFields["id"])
    else {
      return nil
    }

    return RingPayload(
      eventId: eventId,
      serverCallId: serverCallId,
      caller: Participant(
        id: callerId,
        displayName: nonEmptyString(callerFields["displayName"]),
        phoneNumber: nonEmptyString(callerFields["phoneNumber"]),
        email: nonEmptyString(callerFields["email"])
      ),
      hasVideo: fields["hasVideo"] as? Bool ?? false,
      metadata: fields["metadata"] as? [String: Any]
    )
  }

  private static func nonEmptyString(_ value: Any?) -> String? {
    guard let string = value as? String, !string.isEmpty else {
      return nil
    }
    return string
  }
}
