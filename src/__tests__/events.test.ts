import { ALL_CALL_EVENT_NAMES, CALL_EVENTS } from "../events";

describe("CALL_EVENTS", () => {
  it("matches the native event-name contract exactly", () => {
    // These strings are duplicated in Swift (CKEvent) and Kotlin (CKEvents);
    // this snapshot guards against accidental drift on the JS side.
    expect(CALL_EVENTS).toEqual({
      incomingCall: "onIncomingCall",
      callAnswered: "onCallAnswered",
      callEnded: "onCallEnded",
      outgoingCallStarted: "onOutgoingCallStarted",
      muteChanged: "onMuteChanged",
      holdChanged: "onHoldChanged",
      dtmf: "onDtmf",
      audioSessionActivated: "onAudioSessionActivated",
      audioSessionDeactivated: "onAudioSessionDeactivated",
      audioRouteChanged: "onAudioRouteChanged",
      voipTokenUpdated: "onVoipTokenUpdated",
    });
  });

  it("exposes 11 unique event names", () => {
    expect(ALL_CALL_EVENT_NAMES).toHaveLength(11);
    expect(new Set(ALL_CALL_EVENT_NAMES).size).toBe(11);
  });
});
