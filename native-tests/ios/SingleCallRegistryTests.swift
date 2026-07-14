import Dispatch
import XCTest

@testable import ExpoCallKitCallRegistry

final class SingleCallRegistryTests: XCTestCase {
  func testConcurrentAdmissionHasExactlyOneWinner() {
    let registry = SingleCallRegistry<Int>()
    let winnersLock = NSLock()
    var winners: [(UUID, Int)] = []

    DispatchQueue.concurrentPerform(iterations: 1_000) { value in
      let id = UUID()
      if registry.reserveIfIdle(id: id, value: value) {
        winnersLock.lock()
        winners.append((id, value))
        winnersLock.unlock()
      }
    }

    XCTAssertEqual(winners.count, 1)
    guard let winner = winners.first else {
      return
    }
    XCTAssertEqual(registry.value(for: winner.0), winner.1)
  }

  func testReservationBlocksUntilTheSameCallIsRemoved() {
    let registry = SingleCallRegistry<String>()
    let firstId = UUID()
    let secondId = UUID()

    XCTAssertTrue(registry.reserveIfIdle(id: firstId, value: "first"))
    XCTAssertFalse(registry.reserveIfIdle(id: secondId, value: "second"))
    XCTAssertNil(registry.remove(secondId))
    XCTAssertFalse(registry.reserveIfIdle(id: secondId, value: "second"))

    XCTAssertEqual(registry.remove(firstId), "first")
    XCTAssertTrue(registry.reserveIfIdle(id: secondId, value: "second"))
    XCTAssertEqual(registry.first(), "second")
  }

  func testMutationIsScopedToTheReservedCall() {
    let registry = SingleCallRegistry<Int>()
    let id = UUID()

    XCTAssertTrue(registry.reserveIfIdle(id: id, value: 1))
    XCTAssertNil(registry.mutate(UUID()) { $0 = 99 })
    XCTAssertEqual(registry.mutate(id) { $0 += 1 }, 2)
    XCTAssertTrue(registry.contains(id))
    XCTAssertEqual(registry.value(for: id), 2)
  }
}
