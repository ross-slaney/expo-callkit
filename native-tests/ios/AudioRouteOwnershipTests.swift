import XCTest

@testable import ExpoCallKitAudioRouteOwnership

final class AudioRouteOwnershipTests: XCTestCase {
  func testStaleMutationCannotCrossIntoTheNextCall() {
    let ownership = AudioRouteOwnership()
    let ended = UUID()
    let next = UUID()
    let staleReachedSession = expectation(description: "stale mutation stayed blocked")
    staleReachedSession.isInverted = true

    ownership.systemActivated(for: ended)
    XCTAssertTrue(ownership.invalidate(ended))
    XCTAssertTrue(ownership.bindIfSystemActive(next))

    let result: Bool? = ownership.withOwnedCall(ended) {
      staleReachedSession.fulfill()
      return true
    }

    XCTAssertNil(result)
    XCTAssertTrue(ownership.snapshot().owns(next))
    wait(for: [staleReachedSession], timeout: 0.01)
  }

  func testInvalidationWaitsForAnInFlightMutationBeforeRebinding() {
    let ownership = AudioRouteOwnership()
    let ended = UUID()
    let next = UUID()
    let mutationStarted = DispatchSemaphore(value: 0)
    let releaseMutation = DispatchSemaphore(value: 0)
    let invalidationFinished = DispatchSemaphore(value: 0)

    ownership.systemActivated(for: ended)

    DispatchQueue.global().async {
      _ = ownership.withOwnedCall(ended) {
        mutationStarted.signal()
        releaseMutation.wait()
      }
    }
    XCTAssertEqual(mutationStarted.wait(timeout: .now() + 1), .success)

    DispatchQueue.global().async {
      _ = ownership.invalidate(ended)
      invalidationFinished.signal()
    }
    XCTAssertEqual(invalidationFinished.wait(timeout: .now() + 0.02), .timedOut)

    releaseMutation.signal()
    XCTAssertEqual(invalidationFinished.wait(timeout: .now() + 1), .success)
    XCTAssertTrue(ownership.bindIfSystemActive(next))
    XCTAssertTrue(ownership.snapshot().owns(next))
  }

  func testSystemDeactivationClearsCurrentOwnership() {
    let ownership = AudioRouteOwnership()
    let callId = UUID()

    ownership.systemActivated(for: callId)
    XCTAssertTrue(ownership.snapshot().owns(callId))

    ownership.systemDeactivated()
    XCTAssertFalse(ownership.snapshot().owns(callId))
    XCTAssertFalse(ownership.bindIfSystemActive(callId))
  }
}
