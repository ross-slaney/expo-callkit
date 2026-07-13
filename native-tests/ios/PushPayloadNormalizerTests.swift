import XCTest

@testable import ExpoCallKitPushPayload

final class PushPayloadNormalizerTests: XCTestCase {
  func testCanonicalEnvelopeRemainsCompatibleAndPreservesRawBody() throws {
    let envelope: [AnyHashable: Any] = [
      "aps": ["alert": "Incoming call"],
      "incomingCall": [
        "eventId": "event-1",
        "serverCallId": "server-1",
        "caller": ["id": "caller-1", "displayName": "Jane"],
        "metadata": ["bookingId": 42],
      ],
    ]

    let normalized = try XCTUnwrap(PushPayloadNormalizer.normalize(envelope))
    XCTAssertEqual(normalized.eventId, "event-1")
    XCTAssertEqual(normalized.serverCallId, "server-1")
    XCTAssertEqual(normalized.callerName, "Jane")
    XCTAssertEqual(normalized.metadata?["bookingId"] as? Int, 42)
    XCTAssertNotNil(normalized.rawPayload["aps"] as? [String: Any])
  }

  func testVoiceMetadataNormalizesWithoutAProviderDependency() throws {
    let callId = "87654321-dcba-4321-dcba-0987654321fe"
    let envelope: [AnyHashable: Any] = [
      "metadata": [
        "voice_sdk_id": "12345678-abcd-1234-abcd-1234567890ab",
        "call_id": callId,
        "caller_name": "Test Caller",
        "caller_number": "+14085550123",
        "nested": ["attempt": 2],
      ],
    ]

    let normalized = try XCTUnwrap(PushPayloadNormalizer.normalize(envelope))
    XCTAssertEqual(normalized.eventId, callId)
    XCTAssertEqual(normalized.serverCallId, callId)
    XCTAssertEqual(normalized.callerId, "+14085550123")
    XCTAssertEqual(normalized.callerName, "Test Caller")
    XCTAssertEqual(normalized.callerNumber, "+14085550123")
    XCTAssertEqual(normalized.callId?.uuidString.lowercased(), callId)
    XCTAssertEqual(
      normalized.metadata?["voice_sdk_id"] as? String,
      "12345678-abcd-1234-abcd-1234567890ab"
    )
    XCTAssertNotNil(normalized.rawPayload["metadata"] as? [String: Any])
  }

  func testOpaqueCallIdsProduceStableDistinctCallKitUuids() throws {
    let first = try XCTUnwrap(PushPayloadNormalizer.uuid(for: "opaque-call-a"))
    XCTAssertEqual(first, PushPayloadNormalizer.uuid(for: "opaque-call-a"))
    XCTAssertNotEqual(first, PushPayloadNormalizer.uuid(for: "opaque-call-b"))
    XCTAssertNil(PushPayloadNormalizer.uuid(for: "  "))
  }

  func testInvalidEnvelopeIsRejected() {
    XCTAssertNil(PushPayloadNormalizer.normalize(["metadata": ["caller_name": "No call id"]]))
    XCTAssertNil(PushPayloadNormalizer.normalize(["unexpected": true]))
  }
}
