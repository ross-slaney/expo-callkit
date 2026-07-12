import AVFoundation
import CallKit
import Foundation

extension CallCenter: CXProviderDelegate {
  public func providerDidReset(_ provider: CXProvider) {
    NSLog("[ExpoCallKit] CXProvider reset — failing all calls")
    cancelAllRingTimeouts()
    Task {
      await PendingAnswers.shared.abandonAll()
    }
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
    Task {
      let (requestId, outcome) = await PendingAnswers.shared.register(
        callId: id,
        timeout: CallKitSetup.answerFulfillTimeout
      )

      EventHub.shared.emit(CKEvent.callAnswered, [
        "callId": id.uuidString.lowercased(),
        "requestId": requestId.uuidString.lowercased(),
      ])

      switch await outcome.value {
      case .acknowledged:
        self.mutateCall(id) {
          $0.status = .connected
          $0.connectedAt = Date()
        }
        action.fulfill()
      case .rejected, .timedOut:
        // Failing the answer makes CallKit end the call, which arrives as a
        // CXEndCallAction and runs the normal teardown path.
        action.fail()
      }
    }
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
