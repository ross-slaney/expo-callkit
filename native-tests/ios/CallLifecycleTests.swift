import XCTest

@testable import ExpoCallKitCallLifecycle

final class CallLifecycleTests: XCTestCase {
  func testTerminalPushEndsEveryNotYetConnectedState() {
    XCTAssertTrue(CallStatus.ringing.shouldEndForTerminalPush)
    XCTAssertTrue(CallStatus.connecting.shouldEndForTerminalPush)
  }

  func testTerminalPushCannotEndConnectedOrAlreadyEndedState() {
    XCTAssertFalse(CallStatus.connected.shouldEndForTerminalPush)
    XCTAssertFalse(CallStatus.ended.shouldEndForTerminalPush)
  }
}
