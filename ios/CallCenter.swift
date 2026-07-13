import CallKit
import Foundation
import UIKit

/// Owns the CXProvider/CXCallController pair and all native call state.
///
/// Single-call model: `maximumCallGroups = 1` and one call per group. A
/// second incoming push while busy is reported to CallKit (Apple requires a
/// report per VoIP push) and immediately rung out as unanswered.
final class CallCenter: NSObject {
  static let shared = CallCenter()

  let provider: CXProvider
  private let transactionController = CXCallController()

  private let calls = SingleCallRegistry<ActiveCall>()
  private let timeoutLock = NSLock()
  private var ringTimeouts: [UUID: Task<Void, Never>] = [:]

  private override init() {
    let configuration = CXProviderConfiguration()
    configuration.supportsVideo = true
    configuration.maximumCallGroups = 1
    configuration.maximumCallsPerCallGroup = 1
    configuration.supportedHandleTypes = [.phoneNumber, .generic]
    configuration.includesCallsInRecents = true
    if let ringtone = CallKitSetup.ringtone {
      configuration.ringtoneSound = ringtone
    }
    if let icon = UIImage(named: "CallKitIcon") {
      configuration.iconTemplateImageData = icon.pngData()
    }

    provider = CXProvider(configuration: configuration)
    super.init()
    provider.setDelegate(self, queue: nil)
  }

  // MARK: - Registry

  func call(withId id: UUID) -> ActiveCall? {
    calls.value(for: id)
  }

  func firstCall() -> ActiveCall? {
    calls.first()
  }

  @discardableResult
  func reserveIfIdle(_ call: ActiveCall) -> Bool {
    calls.reserveIfIdle(id: call.id, value: call)
  }

  @discardableResult
  func mutateCall(_ id: UUID, _ transform: (inout ActiveCall) -> Void) -> ActiveCall? {
    calls.mutate(id, transform)
  }

  @discardableResult
  func removeCall(_ id: UUID) -> ActiveCall? {
    calls.remove(id)
  }

  /// TS `CallSession` snapshot of the current call, or nil when idle.
  func activeCallSession() -> [String: Any]? {
    firstCall()?.asSessionDictionary()
  }

  // MARK: - Incoming calls

  /// JS-initiated report (signaling discovered a call without a VoIP push).
  func reportIncomingCallFromJS(_ payload: RingPayload) async throws -> UUID {
    let call = makeIncomingCall(payload)
    guard reserveIfIdle(call) else {
      throw CallExistsException()
    }
    return try await withCheckedThrowingContinuation { continuation in
      reportReservedIncomingCall(call, payload: payload) { result in
        switch result {
        case .success(let id):
          continuation.resume(returning: id)
        case .failure(let error):
          continuation.resume(throwing: CallKitRejectedException(error.localizedDescription))
        }
      }
    }
  }

  /// Push-initiated report. Never throws: if busy, the push is still
  /// reported (Apple rule) and immediately rung out.
  func reportIncomingCallFromPush(_ payload: RingPayload, completion: @escaping () -> Void) {
    let call = makeIncomingCall(payload)
    guard reserveIfIdle(call) else {
      reportDiscardedPush(
        callerName: payload.caller.displayName,
        reason: .unanswered,
        completion: completion
      )
      return
    }
    reportReservedIncomingCall(call, payload: payload) { _ in
      completion()
    }
  }

  private func makeIncomingCall(_ payload: RingPayload) -> ActiveCall {
    ActiveCall(
      id: payload.callId ?? UUID(),
      origin: .incoming,
      status: .ringing,
      remoteParty: payload.caller,
      serverCallId: payload.serverCallId,
      metadata: payload.metadata,
      hasVideo: payload.hasVideo,
      incomingPayload: payload
    )
  }

  /// Reports a call that already owns the single admission slot.
  /// `reportNewIncomingCall` is invoked synchronously — Swift-concurrency
  /// hops are avoided so the CallKit report lands before a VoIP push
  /// completion handler runs, even on a killed-state launch.
  private func reportReservedIncomingCall(
    _ call: ActiveCall,
    payload: RingPayload,
    completion: @escaping (Result<UUID, Error>) -> Void
  ) {
    let id = call.id

    AudioSessionCoordinator.shared.prewarm()

    let update = CXCallUpdate()
    update.remoteHandle = payload.caller.asHandle()
    update.hasVideo = payload.hasVideo
    update.supportsHolding = true
    update.supportsGrouping = false
    update.supportsUngrouping = false
    update.supportsDTMF = true
    if let displayName = payload.caller.displayName {
      update.localizedCallerName = displayName
    }

    provider.reportNewIncomingCall(with: id, update: update) { error in
      if let error {
        NSLog("[ExpoCallKit] reportNewIncomingCall failed: \(error.localizedDescription)")
        self.removeCall(id)
        completion(.failure(error))
        return
      }

      // A provider reset or end can remove the reservation before CallKit's
      // completion arrives. Never resurrect or emit a call that already lost
      // ownership of the slot.
      guard self.call(withId: id) != nil else {
        let cancellation = NSError(
          domain: "dev.rossslaney.expocallkit",
          code: 1,
          userInfo: [NSLocalizedDescriptionKey: "Incoming call admission was cancelled"]
        )
        completion(.failure(cancellation))
        return
      }

      var event: [String: Any] = [
        "callId": id.uuidString.lowercased(),
        "payload": payload.asDictionary(),
      ]
      if let rawPushPayload = payload.rawPushPayload {
        event["rawPushPayload"] = rawPushPayload
      }
      EventHub.shared.emit(CKEvent.incomingCall, event)

      self.scheduleRingTimeout(for: id, seconds: CallKitSetup.incomingTimeout)
      completion(.success(id))
    }
  }

  /// Satisfies Apple's report-per-push rule for pushes we cannot honor
  /// (unparseable payload, duplicate delivery, busy): report a call, then end
  /// it immediately with the given reason.
  func reportDiscardedPush(
    callerName: String?,
    reason: EndReason,
    completion: @escaping () -> Void
  ) {
    let id = UUID()
    let update = CXCallUpdate()
    update.remoteHandle = CXHandle(type: .generic, value: id.uuidString)
    update.localizedCallerName = callerName ?? "Unknown Caller"

    provider.reportNewIncomingCall(with: id, update: update) { _ in
      self.provider.reportCall(with: id, endedAt: nil, reason: reason.cxReason)
      completion()
    }
  }

  // MARK: - Outgoing calls

  func startOutgoingCall(
    recipient: Participant,
    hasVideo: Bool,
    metadata: [String: Any]?
  ) async throws -> UUID {
    let id = UUID()
    let call = ActiveCall(
      id: id,
      origin: .outgoing,
      status: .connecting,
      remoteParty: recipient,
      serverCallId: nil,
      metadata: metadata,
      hasVideo: hasVideo,
      incomingPayload: nil
    )
    guard reserveIfIdle(call) else {
      throw CallExistsException()
    }

    AudioSessionCoordinator.shared.prewarm()

    let action = CXStartCallAction(call: id, handle: recipient.asHandle())
    action.isVideo = hasVideo
    if let displayName = recipient.displayName {
      action.contactIdentifier = displayName
    }

    do {
      try await requestTransaction(CXTransaction(action: action))
    } catch {
      removeCall(id)
      throw CallKitRejectedException(error.localizedDescription)
    }
    return id
  }

  func reportOutgoingConnected(_ id: UUID) throws {
    guard call(withId: id) != nil else {
      throw NoSuchCallException(id.uuidString.lowercased())
    }
    let now = Date()
    cancelRingTimeout(for: id)
    provider.reportOutgoingCall(with: id, connectedAt: now)
    mutateCall(id) {
      $0.status = .connected
      $0.connectedAt = now
    }
  }

  // MARK: - Ending calls

  /// Local end/decline via a CXEndCallAction transaction. The delegate's
  /// perform handler does the actual cleanup + event emission.
  func endCall(_ id: UUID) async throws {
    guard call(withId: id) != nil else {
      throw NoSuchCallException(id.uuidString.lowercased())
    }
    do {
      try await requestTransaction(CXTransaction(action: CXEndCallAction(call: id)))
    } catch {
      throw CallKitRejectedException(error.localizedDescription)
    }
  }

  /// Externally-caused end (remote hangup, answered elsewhere, ...).
  func reportCallEnded(_ id: UUID, reason: EndReason) throws {
    guard call(withId: id) != nil else {
      throw NoSuchCallException(id.uuidString.lowercased())
    }
    concludeCall(id, reason: reason, reportToProvider: true)
  }

  /// Shared teardown: cancels the ring timeout, abandons pending answers,
  /// optionally informs CallKit, emits `onCallEnded`, removes the call.
  func concludeCall(_ id: UUID, reason: EndReason, reportToProvider: Bool) {
    cancelRingTimeout(for: id)
    PendingAnswers.shared.abandon(callId: id)

    guard var ended = removeCall(id) else {
      return
    }
    ended.status = .ended

    if reportToProvider {
      provider.reportCall(with: id, endedAt: nil, reason: reason.cxReason)
    }

    var event: [String: Any] = [
      "callId": id.uuidString.lowercased(),
      "session": ended.asSessionDictionary(),
      "reason": reason.rawValue,
    ]
    if let rawPushPayload = ended.incomingPayload?.rawPushPayload {
      event["rawPushPayload"] = rawPushPayload
    }
    EventHub.shared.emit(CKEvent.callEnded, event)
  }

  // MARK: - Mute / hold

  func setMuted(_ id: UUID, muted: Bool) async throws {
    guard call(withId: id) != nil else {
      throw NoSuchCallException(id.uuidString.lowercased())
    }
    do {
      try await requestTransaction(
        CXTransaction(action: CXSetMutedCallAction(call: id, muted: muted))
      )
    } catch {
      throw CallKitRejectedException(error.localizedDescription)
    }
  }

  func setOnHold(_ id: UUID, onHold: Bool) async throws {
    guard call(withId: id) != nil else {
      throw NoSuchCallException(id.uuidString.lowercased())
    }
    do {
      try await requestTransaction(
        CXTransaction(action: CXSetHeldCallAction(call: id, onHold: onHold))
      )
    } catch {
      throw CallKitRejectedException(error.localizedDescription)
    }
  }

  // MARK: - Timeouts

  func scheduleRingTimeout(for id: UUID, seconds: TimeInterval) {
    cancelRingTimeout(for: id)
    let task = Task { [weak self] in
      try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
      guard !Task.isCancelled, let self else {
        return
      }
      guard let current = self.call(withId: id), current.status != .connected else {
        return
      }
      self.concludeCall(id, reason: .unanswered, reportToProvider: true)
    }
    timeoutLock.lock()
    ringTimeouts[id] = task
    timeoutLock.unlock()
  }

  func cancelRingTimeout(for id: UUID) {
    timeoutLock.lock()
    let task = ringTimeouts.removeValue(forKey: id)
    timeoutLock.unlock()
    task?.cancel()
  }

  func cancelAllRingTimeouts() {
    timeoutLock.lock()
    let tasks = Array(ringTimeouts.values)
    ringTimeouts.removeAll()
    timeoutLock.unlock()
    tasks.forEach { $0.cancel() }
  }

  // MARK: - Transactions

  private func requestTransaction(_ transaction: CXTransaction) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      transactionController.request(transaction) { error in
        if let error {
          continuation.resume(throwing: error)
        } else {
          continuation.resume()
        }
      }
    }
  }
}
