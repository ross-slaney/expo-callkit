import CryptoKit
import Foundation

/// Provider-neutral representation of an incoming VoIP push.
///
/// PushKit wakes the native process before React Native exists. Normalizing the
/// provider payload here lets CallKit ring synchronously while preserving the
/// complete JSON body for the app's signaling/media adapter after JS starts.
struct NormalizedPushRing {
  let eventId: String
  let serverCallId: String
  let callerId: String
  let callerName: String?
  let callerNumber: String?
  let callerEmail: String?
  let hasVideo: Bool
  let metadata: [String: Any]?
  let callId: UUID?
  let rawPayload: [String: Any]
}

enum PushPayloadNormalizer {
  static func normalize(_ envelope: [AnyHashable: Any]) -> NormalizedPushRing? {
    let rawPayload = jsonSafeDictionary(envelope)

    if let incoming = dictionary(envelope["incomingCall"]) {
      return normalizeCanonical(incoming, rawPayload: rawPayload)
    }

    // Recognize by shape rather than provider name. Any service using these
    // common voice metadata keys receives the same canonical call contract.
    if let metadata = dictionary(envelope["metadata"]) {
      return normalizeVoiceMetadata(metadata, rawPayload: rawPayload)
    }

    return nil
  }

  private static func normalizeCanonical(
    _ fields: [AnyHashable: Any],
    rawPayload: [String: Any]
  ) -> NormalizedPushRing? {
    guard
      let eventId = nonEmptyString(fields["eventId"]),
      let serverCallId = nonEmptyString(fields["serverCallId"]),
      let caller = dictionary(fields["caller"]),
      let callerId = nonEmptyString(caller["id"])
    else {
      return nil
    }

    return NormalizedPushRing(
      eventId: eventId,
      serverCallId: serverCallId,
      callerId: callerId,
      callerName: nonEmptyString(caller["displayName"]),
      callerNumber: nonEmptyString(caller["phoneNumber"]),
      callerEmail: nonEmptyString(caller["email"]),
      hasVideo: fields["hasVideo"] as? Bool ?? false,
      metadata: dictionary(fields["metadata"]).map(jsonSafeDictionary),
      callId: uuid(for: serverCallId),
      rawPayload: rawPayload
    )
  }

  private static func normalizeVoiceMetadata(
    _ metadata: [AnyHashable: Any],
    rawPayload: [String: Any]
  ) -> NormalizedPushRing? {
    guard let callId = nonEmptyString(metadata["call_id"] ?? metadata["callId"]) else {
      return nil
    }

    let callerName = nonEmptyString(metadata["caller_name"] ?? metadata["callerName"])
    let callerNumber = nonEmptyString(metadata["caller_number"] ?? metadata["callerNumber"])
    let callerId = callerNumber ?? callerName ?? callId
    let eventId = nonEmptyString(metadata["event_id"] ?? metadata["eventId"]) ?? callId

    return NormalizedPushRing(
      eventId: eventId,
      serverCallId: callId,
      callerId: callerId,
      callerName: callerName,
      callerNumber: callerNumber,
      callerEmail: nil,
      hasVideo: metadata["has_video"] as? Bool ?? metadata["hasVideo"] as? Bool ?? false,
      metadata: jsonSafeDictionary(metadata),
      callId: uuid(for: callId),
      rawPayload: rawPayload
    )
  }

  /// A provider call id is normally already a UUID. The deterministic fallback
  /// keeps duplicate deliveries attached to the same CallKit call even when a
  /// provider uses an opaque id.
  static func uuid(for stableId: String) -> UUID? {
    let trimmed = stableId.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else {
      return nil
    }
    if let uuid = UUID(uuidString: trimmed) {
      return uuid
    }

    var bytes = Array(SHA256.hash(data: Data(trimmed.utf8)).prefix(16))
    bytes[6] = (bytes[6] & 0x0f) | 0x50
    bytes[8] = (bytes[8] & 0x3f) | 0x80
    let tuple: uuid_t = (
      bytes[0], bytes[1], bytes[2], bytes[3],
      bytes[4], bytes[5], bytes[6], bytes[7],
      bytes[8], bytes[9], bytes[10], bytes[11],
      bytes[12], bytes[13], bytes[14], bytes[15]
    )
    return UUID(uuid: tuple)
  }

  static func jsonSafeDictionary(_ source: [AnyHashable: Any]) -> [String: Any] {
    var result: [String: Any] = [:]
    for (key, value) in source {
      result[String(describing: key)] = jsonSafe(value)
    }
    return result
  }

  private static func jsonSafe(_ value: Any) -> Any {
    if let dictionary = dictionary(value) {
      return jsonSafeDictionary(dictionary)
    }
    if let array = value as? [Any] {
      return array.map(jsonSafe)
    }
    if value is NSNull || value is String || value is Bool || value is NSNumber {
      return value
    }
    // APNs payloads are JSON. This is defensive for synthetic/test pushes.
    return String(describing: value)
  }

  private static func dictionary(_ value: Any?) -> [AnyHashable: Any]? {
    value as? [AnyHashable: Any]
  }

  private static func nonEmptyString(_ value: Any?) -> String? {
    guard let string = value as? String else {
      return nil
    }
    let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? nil : trimmed
  }
}
