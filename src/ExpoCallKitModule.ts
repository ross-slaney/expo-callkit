import { NativeModule, requireNativeModule } from "expo";

import type {
  CallEndReason,
  CallKitPermissions,
  CallParticipant,
  CallSession,
  AudioRouteState,
  ExpoCallKitEvents,
  IncomingCallPayload,
  OutgoingCallOptions,
  VoipToken,
} from "./ExpoCallKit.types";

declare class ExpoCallKitNativeModule extends NativeModule<ExpoCallKitEvents> {
  reportIncomingCall(payload: IncomingCallPayload): Promise<string>;
  startOutgoingCall(
    recipient: CallParticipant,
    options: OutgoingCallOptions,
  ): Promise<string>;
  reportOutgoingCallConnected(callId: string): Promise<void>;
  answerAcknowledged(requestId: string): Promise<void>;
  answerFailed(requestId: string): Promise<void>;
  endCall(callId: string): Promise<void>;
  reportCallEnded(callId: string, reason: CallEndReason): Promise<void>;
  setMuted(callId: string, muted: boolean): Promise<void>;
  setOnHold(callId: string, onHold: boolean): Promise<void>;
  getAudioRouteState(): Promise<AudioRouteState>;
  selectAudioRoute(callId: string, routeId: string): Promise<void>;
  setSpeakerEnabled(callId: string, enabled: boolean): Promise<void>;
  getActiveCall(): Promise<CallSession | null>;
  getVoipToken(): VoipToken | null;
  registerVoipPushes(): void;
  configureAudioSession(): void;
  requestPermissions(): Promise<CallKitPermissions>;
}

export default requireNativeModule<ExpoCallKitNativeModule>("ExpoCallKit");
