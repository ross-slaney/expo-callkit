import XCTest

@testable import ExpoCallKitPendingAnswers

final class PendingAnswersTests: XCTestCase {
  func testImmediateAcknowledgementCannotBeatRegistration() async {
    let registry = PendingAnswers()

    // Exercise the cold-start edge repeatedly: acknowledgement happens on the
    // very next instruction after register returns.
    for _ in 0..<1_000 {
      let callId = UUID()
      let pending = registry.register(callId: callId, timeout: 5)

      XCTAssertEqual(registry.acknowledge(pending.requestId), callId)
      let outcome = await pending.outcome.value
      XCTAssertEqual(outcome, .acknowledged)
    }
  }

  func testOnlyFirstResolutionWins() async {
    let registry = PendingAnswers()
    let callId = UUID()
    let pending = registry.register(callId: callId, timeout: 5)

    XCTAssertEqual(registry.fail(pending.requestId), callId)
    XCTAssertNil(registry.acknowledge(pending.requestId))
    let outcome = await pending.outcome.value
    XCTAssertEqual(outcome, .rejected)
  }

  func testTimeoutResolvesAndRemovesEntry() async {
    let registry = PendingAnswers()
    let pending = registry.register(callId: UUID(), timeout: 0.01)

    let outcome = await pending.outcome.value
    XCTAssertEqual(outcome, .timedOut)
    XCTAssertNil(registry.acknowledge(pending.requestId))
  }

  func testSystemTimeoutWinsSynchronously() async {
    let registry = PendingAnswers()
    let callId = UUID()
    let pending = registry.register(callId: callId, timeout: 5)

    XCTAssertTrue(registry.systemTimedOut(callId: callId))
    let outcome = await pending.outcome.value
    XCTAssertEqual(outcome, .systemTimedOut)
    XCTAssertNil(registry.acknowledge(pending.requestId))
  }

  func testCallEndAndProviderResetHaveDistinctOutcomes() async {
    let registry = PendingAnswers()
    let firstCall = UUID()
    let first = registry.register(callId: firstCall, timeout: 5)
    registry.abandon(callId: firstCall)
    let firstOutcome = await first.outcome.value
    XCTAssertEqual(firstOutcome, .callEnded)

    let second = registry.register(callId: UUID(), timeout: 5)
    registry.abandonAll()
    let secondOutcome = await second.outcome.value
    XCTAssertEqual(secondOutcome, .providerReset)
  }
}
