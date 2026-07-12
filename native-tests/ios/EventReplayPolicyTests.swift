import XCTest

@testable import ExpoCallKitEventPolicy

final class EventReplayPolicyTests: XCTestCase {
  func testOnlyStateReconstructionEventsAreReplayable() {
    XCTAssertEqual(EventReplayPolicy.limits[CKEvent.incomingCall], 1)
    XCTAssertEqual(EventReplayPolicy.limits[CKEvent.callAnswered], 1)
    XCTAssertEqual(EventReplayPolicy.limits[CKEvent.callEnded], 1)
    XCTAssertEqual(EventReplayPolicy.limits[CKEvent.voipTokenUpdated], 1)
  }

  func testAudioLifecycleEventsAreNeverReplayed() {
    XCTAssertNil(EventReplayPolicy.limits[CKEvent.audioSessionActivated])
    XCTAssertNil(EventReplayPolicy.limits[CKEvent.audioSessionDeactivated])
  }
}
