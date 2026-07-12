/**
 * A person on either side of a call. `id` is your app's stable identifier for
 * the person (an ACS identity, a user id, etc). The optional handles control
 * how the OS renders them in the system call UI.
 */
export type CallParticipant = {
  id: string;
  displayName?: string;
  phoneNumber?: string;
  email?: string;
};

/**
 * Payload describing an incoming call, either parsed natively from a VoIP
 * push (iOS) or handed to `reportIncomingCall` by your own push layer
 * (Android, or iOS when testing without pushes).
 */
export type IncomingCallPayload = {
  /** Unique id for this ring event. Used to deduplicate re-delivered pushes. */
  eventId: string;
  /** Your backend's id for the call (distinct from the OS-assigned call id). */
  serverCallId: string;
  caller: CallParticipant;
  hasVideo?: boolean;
  /** Opaque app data carried through to events untouched. */
  metadata?: Record<string, unknown>;
};

export type CallOrigin = "incoming" | "outgoing";

export type CallStatus = "ringing" | "connecting" | "connected" | "ended";

/** Why a call ended. */
export type CallEndReason =
  | "failed"
  | "remoteEnded"
  | "unanswered"
  | "answeredElsewhere"
  | "declinedElsewhere"
  | "local"
  | "unknown";

/** Snapshot of a native call session. */
export type CallSession = {
  /** OS-assigned call id (UUID string). All module functions key off this. */
  id: string;
  origin: CallOrigin;
  status: CallStatus;
  /** Remote party for incoming calls. */
  caller?: CallParticipant;
  /** Remote party for outgoing calls. */
  recipient?: CallParticipant;
  serverCallId?: string;
  metadata?: Record<string, unknown>;
  isMuted: boolean;
  isOnHold: boolean;
  /** ISO 8601 timestamp set once the call reaches "connected". */
  connectedAt?: string;
};

export type VoipTokenType = "apns-voip" | "fcm";

export type VoipToken = {
  token: string;
  type: VoipTokenType;
};

/**
 * Delivery metadata attached to every event. `flushed` is true when the event
 * was buffered natively (fired before JS mounted a listener, e.g. during a
 * cold start) and replayed later; `timestamp` is when it originally fired.
 */
export type EventMeta = {
  flushed: boolean;
  timestamp: string;
};

export type IncomingCallEvent = {
  callId: string;
  payload: IncomingCallPayload;
  meta: EventMeta;
};

export type CallAnsweredEvent = {
  callId: string;
  /**
   * Pass to `answerAcknowledged` once your media layer is connected, or to
   * `answerFailed` if connecting failed. On iOS the system answer action is
   * held open until one of the two is called (or the fulfill timeout fires).
   */
  requestId: string;
  meta: EventMeta;
};

export type CallEndedEvent = {
  callId: string;
  session: CallSession;
  reason: CallEndReason;
  meta: EventMeta;
};

export type OutgoingCallStartedEvent = {
  callId: string;
  meta: EventMeta;
};

export type MuteChangedEvent = {
  callId: string;
  isMuted: boolean;
  meta: EventMeta;
};

export type HoldChangedEvent = {
  callId: string;
  isOnHold: boolean;
  meta: EventMeta;
};

export type DtmfEvent = {
  callId: string;
  digits: string;
  meta: EventMeta;
};

export type AudioSessionEvent = {
  meta: EventMeta;
};

export type VoipTokenUpdatedEvent = {
  /** Null when the OS invalidated the token. */
  token: string | null;
  type: VoipTokenType;
  meta: EventMeta;
};

export type ExpoCallKitEvents = {
  onIncomingCall: (event: IncomingCallEvent) => void;
  onCallAnswered: (event: CallAnsweredEvent) => void;
  onCallEnded: (event: CallEndedEvent) => void;
  onOutgoingCallStarted: (event: OutgoingCallStartedEvent) => void;
  onMuteChanged: (event: MuteChangedEvent) => void;
  onHoldChanged: (event: HoldChangedEvent) => void;
  onDtmf: (event: DtmfEvent) => void;
  onAudioSessionActivated: (event: AudioSessionEvent) => void;
  onAudioSessionDeactivated: (event: AudioSessionEvent) => void;
  onVoipTokenUpdated: (event: VoipTokenUpdatedEvent) => void;
};

export type PermissionResult = "granted" | "denied" | "undetermined";

export type CallKitPermissions = {
  /** POST_NOTIFICATIONS state on Android 13+; always "granted" elsewhere. */
  notifications: PermissionResult;
};

export type OutgoingCallOptions = {
  hasVideo?: boolean;
  metadata?: Record<string, unknown>;
};
