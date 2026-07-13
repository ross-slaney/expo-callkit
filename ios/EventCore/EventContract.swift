import Foundation

/// Event name constants shared with `src/events.ts` and Android.
enum CKEvent {
  static let incomingCall = "onIncomingCall"
  static let callAnswered = "onCallAnswered"
  static let callEnded = "onCallEnded"
  static let outgoingCallStarted = "onOutgoingCallStarted"
  static let muteChanged = "onMuteChanged"
  static let holdChanged = "onHoldChanged"
  static let dtmf = "onDtmf"
  static let audioSessionActivated = "onAudioSessionActivated"
  static let audioSessionDeactivated = "onAudioSessionDeactivated"
  static let audioRouteChanged = "onAudioRouteChanged"
  static let voipTokenUpdated = "onVoipTokenUpdated"

  static let all: [String] = [
    incomingCall, callAnswered, callEnded, outgoingCallStarted,
    muteChanged, holdChanged, dtmf,
    audioSessionActivated, audioSessionDeactivated, audioRouteChanged, voipTokenUpdated,
  ]
}

/// Native events that are safe to replay after JS starts observing.
///
/// Audio activation is deliberately realtime-only. Replaying an old
/// activation after CallKit has already deactivated the session can make a
/// late listener start audio against an inactive system session.
enum EventReplayPolicy {
  static let limits: [String: Int] = [
    CKEvent.incomingCall: 1,
    CKEvent.callAnswered: 1,
    CKEvent.callEnded: 1,
    CKEvent.voipTokenUpdated: 1,
  ]
}
