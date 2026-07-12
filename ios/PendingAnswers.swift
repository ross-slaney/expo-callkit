import Foundation

/// Registry of answer actions waiting on the app's media layer.
///
/// When the user answers via the system UI, the `CXAnswerCallAction` is kept
/// un-fulfilled while JS connects call media. Each held action is tracked
/// here under a request id; JS resolves it through `answerAcknowledged` /
/// `answerFailed`, and a timeout guarantees the action is never held forever.
actor PendingAnswers {
  static let shared = PendingAnswers()

  enum Outcome {
    case acknowledged
    case rejected
    case timedOut
  }

  private struct Entry {
    let callId: UUID
    let continuation: CheckedContinuation<Outcome, Never>
    let timeoutTask: Task<Void, Never>
  }

  private var entries: [UUID: Entry] = [:]

  private init() {}

  /// Registers a new pending answer for `callId`.
  ///
  /// - Returns: the request id to surface to JS, plus a task that resolves
  ///   with the final outcome (acknowledge, fail, or timeout).
  func register(
    callId: UUID,
    timeout: TimeInterval
  ) -> (requestId: UUID, outcome: Task<Outcome, Never>) {
    let requestId = UUID()

    let outcome = Task<Outcome, Never> {
      await withCheckedContinuation { (continuation: CheckedContinuation<Outcome, Never>) in
        Task {
          await self.record(
            requestId: requestId,
            callId: callId,
            continuation: continuation,
            timeout: timeout
          )
        }
      }
    }

    return (requestId, outcome)
  }

  /// Resolves a pending answer as acknowledged. Returns the call id, or nil
  /// when the request already resolved (e.g. timed out).
  func acknowledge(_ requestId: UUID) -> UUID? {
    resolve(requestId, with: .acknowledged)
  }

  /// Resolves a pending answer as failed. Returns the call id, or nil when
  /// the request already resolved.
  func fail(_ requestId: UUID) -> UUID? {
    resolve(requestId, with: .rejected)
  }

  /// Rejects any pending answers attached to `callId` (call ended first).
  func abandon(callId: UUID) {
    let requestIds = entries.filter { $0.value.callId == callId }.map(\.key)
    for requestId in requestIds {
      _ = resolve(requestId, with: .rejected)
    }
  }

  /// Rejects every pending answer (provider reset).
  func abandonAll() {
    let requestIds = Array(entries.keys)
    for requestId in requestIds {
      _ = resolve(requestId, with: .rejected)
    }
  }

  // MARK: - Internals

  private func record(
    requestId: UUID,
    callId: UUID,
    continuation: CheckedContinuation<Outcome, Never>,
    timeout: TimeInterval
  ) {
    let timeoutTask = Task { [weak self] in
      try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
      guard !Task.isCancelled else {
        return
      }
      _ = await self?.resolve(requestId, with: .timedOut)
    }

    entries[requestId] = Entry(
      callId: callId,
      continuation: continuation,
      timeoutTask: timeoutTask
    )
  }

  private func resolve(_ requestId: UUID, with outcome: Outcome) -> UUID? {
    guard let entry = entries.removeValue(forKey: requestId) else {
      return nil
    }
    entry.timeoutTask.cancel()
    entry.continuation.resume(returning: outcome)
    return entry.callId
  }
}
