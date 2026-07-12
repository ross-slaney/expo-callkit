import type { ExpoCallKitEvents } from "./ExpoCallKit.types";

/**
 * Event name constants. These string values are the contract with both native
 * implementations — changing one is a breaking change on every platform.
 */
export const CALL_EVENTS = {
  incomingCall: "onIncomingCall",
  callAnswered: "onCallAnswered",
  callEnded: "onCallEnded",
  outgoingCallStarted: "onOutgoingCallStarted",
  muteChanged: "onMuteChanged",
  holdChanged: "onHoldChanged",
  dtmf: "onDtmf",
  audioSessionActivated: "onAudioSessionActivated",
  audioSessionDeactivated: "onAudioSessionDeactivated",
  voipTokenUpdated: "onVoipTokenUpdated",
} as const satisfies Record<string, keyof ExpoCallKitEvents>;

/** All event names, in a stable order. */
export const ALL_CALL_EVENT_NAMES: readonly (keyof ExpoCallKitEvents)[] =
  Object.values(CALL_EVENTS);
