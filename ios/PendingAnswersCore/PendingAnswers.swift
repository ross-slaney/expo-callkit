import Foundation

/// Registry of answer actions waiting on the app's media layer.
///
/// Registration is deliberately synchronous: the entry is stored before the
/// request id can be emitted to JavaScript. This prevents a fast
/// `answerAcknowledged` call from racing ahead of the registry write during a
/// cold start.
final class PendingAnswers {
  static let shared = PendingAnswers()

  enum Outcome: Equatable {
    case acknowledged
    case rejected
    case timedOut
    case systemTimedOut
    case callEnded
    case providerReset
  }

  private struct Entry {
    let callId: UUID
    let continuation: AsyncStream<Outcome>.Continuation
    var timeoutTask: Task<Void, Never>?
  }

  private let lock = NSLock()
  private var entries: [UUID: Entry] = [:]

  /// Internal rather than private so the native test target can create an
  /// isolated registry. Production code uses `shared`.
  init() {}

  /// Registers a new pending answer for `callId`.
  ///
  /// The registry entry exists before this method returns, so callers may emit
  /// `requestId` immediately without an acknowledgement race.
  func register(
    callId: UUID,
    timeout: TimeInterval
  ) -> (requestId: UUID, outcome: Task<Outcome, Never>) {
    let requestId = UUID()
    let (stream, continuation) = AsyncStream.makeStream(
      of: Outcome.self,
      bufferingPolicy: .bufferingNewest(1)
    )

    lock.lock()
    entries[requestId] = Entry(
      callId: callId,
      continuation: continuation,
      timeoutTask: nil
    )
    lock.unlock()

    // Keep conversion bounded even if a malformed Info.plist bypasses the
    // config plugin. The public plugin currently uses second-scale values.
    let boundedTimeout = min(max(timeout, 0), 86_400)
    let timeoutTask = Task { [weak self] in
      try? await Task.sleep(nanoseconds: UInt64(boundedTimeout * 1_000_000_000))
      guard !Task.isCancelled else {
        return
      }
      _ = self?.resolve(requestId, with: .timedOut)
    }

    lock.lock()
    if var entry = entries[requestId] {
      entry.timeoutTask = timeoutTask
      entries[requestId] = entry
      lock.unlock()
    } else {
      // Defensive: another thread could only resolve here if this method is
      // changed to expose requestId before returning.
      lock.unlock()
      timeoutTask.cancel()
    }

    let outcome = Task<Outcome, Never> {
      var iterator = stream.makeAsyncIterator()
      return await iterator.next() ?? .callEnded
    }

    return (requestId, outcome)
  }

  /// Resolves a pending answer as acknowledged. Returns the call id, or nil
  /// when the request already resolved (for example, after a timeout).
  @discardableResult
  func acknowledge(_ requestId: UUID) -> UUID? {
    resolve(requestId, with: .acknowledged)
  }

  /// Resolves a pending answer as rejected by the app's media layer.
  @discardableResult
  func fail(_ requestId: UUID) -> UUID? {
    resolve(requestId, with: .rejected)
  }

  /// Marks the system CallKit action as timed out. This is synchronous so the
  /// waiter can never fulfill or fail an action after CallKit has timed it out.
  @discardableResult
  func systemTimedOut(callId: UUID) -> Bool {
    resolveAll(for: callId, with: .systemTimedOut) > 0
  }

  /// Resolves pending answers because the call ended through another path.
  func abandon(callId: UUID) {
    _ = resolveAll(for: callId, with: .callEnded)
  }

  /// Resolves every pending answer because CallKit reset the provider.
  func abandonAll() {
    let pending: [Entry]
    lock.lock()
    pending = Array(entries.values)
    entries.removeAll()
    lock.unlock()

    pending.forEach { finish($0, with: .providerReset) }
  }

  // MARK: - Internals

  @discardableResult
  private func resolve(_ requestId: UUID, with outcome: Outcome) -> UUID? {
    let entry: Entry?
    lock.lock()
    entry = entries.removeValue(forKey: requestId)
    lock.unlock()

    guard let entry else {
      return nil
    }
    finish(entry, with: outcome)
    return entry.callId
  }

  private func resolveAll(for callId: UUID, with outcome: Outcome) -> Int {
    let matches: [Entry]
    lock.lock()
    let requestIds = entries.compactMap { requestId, entry in
      entry.callId == callId ? requestId : nil
    }
    matches = requestIds.compactMap { entries.removeValue(forKey: $0) }
    lock.unlock()

    matches.forEach { finish($0, with: outcome) }
    return matches.count
  }

  private func finish(_ entry: Entry, with outcome: Outcome) {
    entry.timeoutTask?.cancel()
    entry.continuation.yield(outcome)
    entry.continuation.finish()
  }
}
