import AVFoundation
import CallKit
import Foundation

extension CallCenter: CXProviderDelegate {
  public func providerDidReset(_ provider: CXProvider) {
    NSLog("[ExpoCallKit] CXProvider reset — failing all calls")
    cancelAllRingTimeouts()
    PendingAnswers.shared.abandonAll()
    // Fail every known call so JS state converges; the provider already
    // dropped them, so nothing is reported back.
    while let call = firstCall() {
      concludeCall(call.id, reason: .failed, reportToProvider: false)
    }
  }

  public func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
    let id = action.callUUID
    guard call(withId: id) != nil else {
      action.fail()
      return
    }

    provider.reportOutgoingCall(with: id, startedConnectingAt: Date())
    mutateCall(id) { $0.status = .connecting }

    EventHub.shared.emit(CKEvent.outgoingCallStarted, [
      "callId": id.uuidString.lowercased(),
    ])

    scheduleRingTimeout(for: id, seconds: CallKitSetup.outgoingTimeout)
    action.fulfill()
  }

  public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
    let id = action.callUUID
    guard call(withId: id) != nil else {
      action.fail()
      return
    }

    cancelRingTimeout(for: id)
    mutateCall(id) { $0.status = .connecting }
    AudioSessionCoordinator.shared.prewarm()

    // Deferred fulfillment: hold the system answer action open while JS
    // connects media, resolved by answerAcknowledged/answerFailed or timeout.
    // Registration and emission must happen in this delegate callback. A
    // deferred Task would leave a window where an end/reset could run first,
    // after which the Task would create an orphan and emit a stale answer.
    let (requestId, outcome) = PendingAnswers.shared.register(
      callId: id,
      timeout: CallKitSetup.answerFulfillTimeout
    )

    EventHub.shared.emit(CKEvent.callAnswered, [
      "callId": id.uuidString.lowercased(),
      "requestId": requestId.uuidString.lowercased(),
    ])

    Task { @MainActor in
      switch await outcome.value {
      case .acknowledged:
        guard self.mutateCall(id, {
          $0.status = .connected
          $0.connectedAt = Date()
        }) != nil else {
          // The call ended after JS acknowledged but before the action could
          // be fulfilled. Its teardown path already owns the CallKit action;
          // do not touch an action that may have timed out in the meantime.
          return
        }
        action.fulfill()
      case .rejected, .timedOut:
        // A CallKit timeout or a separate end action can win after this
        // outcome resolves but before the main-actor waiter resumes.
        guard self.call(withId: id) != nil else {
          return
        }
        // Do not rely on a follow-up CXEndCallAction: CallKit does not
        // guarantee one after a failed answer. End both native state and the
        // system call deterministically.
        action.fail()
        self.concludeCall(id, reason: .failed, reportToProvider: true)
      case .systemTimedOut, .callEnded, .providerReset:
        // CallKit already timed the action out, ended the call, or reset the
        // provider. Apple's contract forbids touching an action after timeout.
        break
      }
    }
  }

  public func provider(_ provider: CXProvider, timedOutPerforming action: CXAction) {
    guard let answerAction = action as? CXAnswerCallAction else {
      return
    }

    let id = answerAction.callUUID
    _ = PendingAnswers.shared.systemTimedOut(callId: id)
    concludeCall(id, reason: .failed, reportToProvider: true)
  }

  public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
    concludeCall(action.callUUID, reason: .local, reportToProvider: false)
    action.fulfill()
  }

  public func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
    guard mutateCall(action.callUUID, { $0.isMuted = action.isMuted }) != nil else {
      action.fail()
      return
    }
    EventHub.shared.emit(CKEvent.muteChanged, [
      "callId": action.callUUID.uuidString.lowercased(),
      "isMuted": action.isMuted,
    ])
    action.fulfill()
  }

  public func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
    guard mutateCall(action.callUUID, { $0.isOnHold = action.isOnHold }) != nil else {
      action.fail()
      return
    }
    EventHub.shared.emit(CKEvent.holdChanged, [
      "callId": action.callUUID.uuidString.lowercased(),
      "isOnHold": action.isOnHold,
    ])
    action.fulfill()
  }

  public func provider(_ provider: CXProvider, perform action: CXPlayDTMFCallAction) {
    EventHub.shared.emit(CKEvent.dtmf, [
      "callId": action.callUUID.uuidString.lowercased(),
      "digits": action.digits,
    ])
    action.fulfill()
  }

  public func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
    AudioSessionCoordinator.shared.systemActivated()
  }

  public func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
    AudioSessionCoordinator.shared.systemDeactivated()
  }
}
