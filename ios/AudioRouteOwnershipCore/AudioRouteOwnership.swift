import Foundation

struct AudioRouteOwnershipSnapshot: Equatable {
  let systemActive: Bool
  let callId: UUID?

  func owns(_ expectedCallId: UUID) -> Bool {
    systemActive && callId == expectedCallId
  }
}

/// Serializes system audio activation, call ownership, and guarded mutations.
///
/// `withOwnedCall` deliberately holds the same lock used by `invalidate`. A
/// stale route request therefore either finishes before the old call releases
/// its slot or observes that it no longer owns audio; it can never mutate the
/// AVAudioSession of the next call.
final class AudioRouteOwnership {
  private let lock = NSRecursiveLock()
  private var systemActive = false
  private var owner: UUID?

  @discardableResult
  func systemActivated(for callId: UUID?) -> AudioRouteOwnershipSnapshot {
    lock.lock()
    defer { lock.unlock() }
    systemActive = true
    owner = callId
    return AudioRouteOwnershipSnapshot(systemActive: systemActive, callId: owner)
  }

  @discardableResult
  func systemDeactivated() -> AudioRouteOwnershipSnapshot {
    lock.lock()
    defer { lock.unlock() }
    let previous = AudioRouteOwnershipSnapshot(systemActive: systemActive, callId: owner)
    systemActive = false
    owner = nil
    return previous
  }

  /// Binds a newly connected call when CallKit kept its global audio session
  /// active across a rapid end/start transition.
  @discardableResult
  func bindIfSystemActive(_ callId: UUID) -> Bool {
    lock.lock()
    defer { lock.unlock() }
    guard systemActive, owner != callId else {
      return false
    }
    owner = callId
    return true
  }

  @discardableResult
  func invalidate(_ callId: UUID) -> Bool {
    lock.lock()
    defer { lock.unlock() }
    guard owner == callId else {
      return false
    }
    owner = nil
    return true
  }

  func snapshot() -> AudioRouteOwnershipSnapshot {
    lock.lock()
    defer { lock.unlock() }
    return AudioRouteOwnershipSnapshot(systemActive: systemActive, callId: owner)
  }

  func withOwnedCall<Result>(
    _ callId: UUID,
    _ operation: () throws -> Result
  ) rethrows -> Result? {
    lock.lock()
    defer { lock.unlock() }
    guard systemActive, owner == callId else {
      return nil
    }
    return try operation()
  }
}
