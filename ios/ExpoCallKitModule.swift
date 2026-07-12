import ExpoModulesCore
import Foundation

// MARK: - Records (JS → native argument shapes)

struct ParticipantRecord: Record {
  @Field
  var id: String = ""

  @Field
  var displayName: String?

  @Field
  var phoneNumber: String?

  @Field
  var email: String?

  func asParticipant() -> Participant {
    Participant(
      id: id,
      displayName: displayName,
      phoneNumber: phoneNumber,
      email: email
    )
  }
}

struct IncomingCallRecord: Record {
  @Field
  var eventId: String = ""

  @Field
  var serverCallId: String = ""

  @Field
  var caller: ParticipantRecord = ParticipantRecord()

  @Field
  var hasVideo: Bool = false

  @Field
  var metadata: [String: Any]?

  func asRingPayload() throws -> RingPayload {
    guard !eventId.isEmpty, !serverCallId.isEmpty, !caller.id.isEmpty else {
      throw InvalidPayloadException()
    }
    return RingPayload(
      eventId: eventId,
      serverCallId: serverCallId,
      caller: caller.asParticipant(),
      hasVideo: hasVideo,
      metadata: metadata
    )
  }
}

struct OutgoingCallOptionsRecord: Record {
  @Field
  var hasVideo: Bool = false

  @Field
  var metadata: [String: Any]?
}

// MARK: - Module

public final class ExpoCallKitModule: Module {
  public func definition() -> ModuleDefinition {
    Name("ExpoCallKit")

    Events(
      CKEvent.incomingCall,
      CKEvent.callAnswered,
      CKEvent.callEnded,
      CKEvent.outgoingCallStarted,
      CKEvent.muteChanged,
      CKEvent.holdChanged,
      CKEvent.dtmf,
      CKEvent.audioSessionActivated,
      CKEvent.audioSessionDeactivated,
      CKEvent.voipTokenUpdated
    )

    OnCreate {
      EventHub.shared.attach(self)
    }

    OnDestroy {
      EventHub.shared.attach(nil)
    }

    // Per-event observation drives the replay buffers in EventHub: each
    // event flushes independently the moment JS mounts its listener.
    OnStartObserving(CKEvent.incomingCall) {
      EventHub.shared.startObserving(CKEvent.incomingCall)
    }
    OnStopObserving(CKEvent.incomingCall) {
      EventHub.shared.stopObserving(CKEvent.incomingCall)
    }

    OnStartObserving(CKEvent.callAnswered) {
      EventHub.shared.startObserving(CKEvent.callAnswered)
    }
    OnStopObserving(CKEvent.callAnswered) {
      EventHub.shared.stopObserving(CKEvent.callAnswered)
    }

    OnStartObserving(CKEvent.callEnded) {
      EventHub.shared.startObserving(CKEvent.callEnded)
    }
    OnStopObserving(CKEvent.callEnded) {
      EventHub.shared.stopObserving(CKEvent.callEnded)
    }

    OnStartObserving(CKEvent.outgoingCallStarted) {
      EventHub.shared.startObserving(CKEvent.outgoingCallStarted)
    }
    OnStopObserving(CKEvent.outgoingCallStarted) {
      EventHub.shared.stopObserving(CKEvent.outgoingCallStarted)
    }

    OnStartObserving(CKEvent.muteChanged) {
      EventHub.shared.startObserving(CKEvent.muteChanged)
    }
    OnStopObserving(CKEvent.muteChanged) {
      EventHub.shared.stopObserving(CKEvent.muteChanged)
    }

    OnStartObserving(CKEvent.holdChanged) {
      EventHub.shared.startObserving(CKEvent.holdChanged)
    }
    OnStopObserving(CKEvent.holdChanged) {
      EventHub.shared.stopObserving(CKEvent.holdChanged)
    }

    OnStartObserving(CKEvent.dtmf) {
      EventHub.shared.startObserving(CKEvent.dtmf)
    }
    OnStopObserving(CKEvent.dtmf) {
      EventHub.shared.stopObserving(CKEvent.dtmf)
    }

    OnStartObserving(CKEvent.audioSessionActivated) {
      EventHub.shared.startObserving(CKEvent.audioSessionActivated)
    }
    OnStopObserving(CKEvent.audioSessionActivated) {
      EventHub.shared.stopObserving(CKEvent.audioSessionActivated)
    }

    OnStartObserving(CKEvent.audioSessionDeactivated) {
      EventHub.shared.startObserving(CKEvent.audioSessionDeactivated)
    }
    OnStopObserving(CKEvent.audioSessionDeactivated) {
      EventHub.shared.stopObserving(CKEvent.audioSessionDeactivated)
    }

    OnStartObserving(CKEvent.voipTokenUpdated) {
      EventHub.shared.startObserving(CKEvent.voipTokenUpdated)
    }
    OnStopObserving(CKEvent.voipTokenUpdated) {
      EventHub.shared.stopObserving(CKEvent.voipTokenUpdated)
    }

    // MARK: Calls

    AsyncFunction("reportIncomingCall") { (record: IncomingCallRecord) -> String in
      let payload = try record.asRingPayload()
      let id = try await CallCenter.shared.reportIncomingCallFromJS(payload)
      return id.uuidString.lowercased()
    }

    AsyncFunction("startOutgoingCall") {
      (recipient: ParticipantRecord, options: OutgoingCallOptionsRecord?) -> String in
      let id = try await CallCenter.shared.startOutgoingCall(
        recipient: recipient.asParticipant(),
        hasVideo: options?.hasVideo ?? false,
        metadata: options?.metadata
      )
      return id.uuidString.lowercased()
    }

    AsyncFunction("reportOutgoingCallConnected") { (callId: String) in
      try CallCenter.shared.reportOutgoingConnected(try Self.parseUuid(callId))
    }

    AsyncFunction("answerAcknowledged") { (requestId: String) in
      // No-op when the request already resolved (raced a timeout).
      _ = await PendingAnswers.shared.acknowledge(try Self.parseUuid(requestId))
    }

    AsyncFunction("answerFailed") { (requestId: String) in
      _ = await PendingAnswers.shared.fail(try Self.parseUuid(requestId))
    }

    AsyncFunction("endCall") { (callId: String) in
      try await CallCenter.shared.endCall(try Self.parseUuid(callId))
    }

    AsyncFunction("reportCallEnded") { (callId: String, reason: String) in
      guard let endReason = EndReason(rawValue: reason) else {
        throw InvalidEndReasonException(reason)
      }
      try CallCenter.shared.reportCallEnded(try Self.parseUuid(callId), reason: endReason)
    }

    AsyncFunction("setMuted") { (callId: String, muted: Bool) in
      try await CallCenter.shared.setMuted(try Self.parseUuid(callId), muted: muted)
    }

    AsyncFunction("setOnHold") { (callId: String, onHold: Bool) in
      try await CallCenter.shared.setOnHold(try Self.parseUuid(callId), onHold: onHold)
    }

    AsyncFunction("getActiveCall") { () -> [String: Any]? in
      CallCenter.shared.activeCallSession()
    }

    // MARK: Push / audio / permissions

    Function("getVoipToken") { () -> [String: Any]? in
      guard let token = VoipPushCoordinator.shared.currentToken else {
        return nil
      }
      return ["token": token, "type": "apns-voip"]
    }

    Function("registerVoipPushes") {
      VoipPushCoordinator.shared.register()
    }

    Function("configureAudioSession") {
      AudioSessionCoordinator.shared.prewarm()
    }

    AsyncFunction("requestPermissions") { () -> [String: String] in
      // Nothing to request on iOS: CallKit UI needs no runtime permission and
      // the mic prompt is triggered by the app's media layer on first use.
      ["notifications": "granted"]
    }
  }

  private static func parseUuid(_ value: String) throws -> UUID {
    guard let uuid = UUID(uuidString: value) else {
      throw InvalidUuidException(value)
    }
    return uuid
  }
}
