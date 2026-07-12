import { NativeModule, registerWebModule } from "expo";

import type {
  CallKitPermissions,
  CallSession,
  ExpoCallKitEvents,
  VoipToken,
} from "./ExpoCallKit.types";

function unavailable(fn: string): Error {
  return new Error(
    `expo-callkit: ${fn} is not available on web. Native call UI requires iOS or Android.`,
  );
}

/**
 * Web stub. Call UX is inherently native; every call-mutating function
 * rejects with a descriptive error, while read-only functions degrade
 * gracefully (no token, no active call, notifications "denied").
 */
class ExpoCallKitWebModule extends NativeModule<ExpoCallKitEvents> {
  reportIncomingCall(): Promise<string> {
    return Promise.reject(unavailable("reportIncomingCall"));
  }
  startOutgoingCall(): Promise<string> {
    return Promise.reject(unavailable("startOutgoingCall"));
  }
  reportOutgoingCallConnected(): Promise<void> {
    return Promise.reject(unavailable("reportOutgoingCallConnected"));
  }
  answerAcknowledged(): Promise<void> {
    return Promise.reject(unavailable("answerAcknowledged"));
  }
  answerFailed(): Promise<void> {
    return Promise.reject(unavailable("answerFailed"));
  }
  endCall(): Promise<void> {
    return Promise.reject(unavailable("endCall"));
  }
  reportCallEnded(): Promise<void> {
    return Promise.reject(unavailable("reportCallEnded"));
  }
  setMuted(): Promise<void> {
    return Promise.reject(unavailable("setMuted"));
  }
  setOnHold(): Promise<void> {
    return Promise.reject(unavailable("setOnHold"));
  }
  getActiveCall(): Promise<CallSession | null> {
    return Promise.resolve(null);
  }
  getVoipToken(): VoipToken | null {
    return null;
  }
  registerVoipPushes(): void {
    // no-op on web
  }
  configureAudioSession(): void {
    // no-op on web
  }
  requestPermissions(): Promise<CallKitPermissions> {
    return Promise.resolve({ notifications: "denied" });
  }
}

export default registerWebModule(ExpoCallKitWebModule, "ExpoCallKit");
