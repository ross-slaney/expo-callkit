import type { EventSubscription } from "expo-modules-core";

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
import ExpoCallKitModule from "./ExpoCallKitModule";
import {
  assertUuid,
  isCallEndReason,
  CallKitValidationError,
  normalizeIncomingCallPayload,
  normalizeParticipant,
} from "./payload";
import {
  CallProviderRouter,
  type CallProviderAdapter,
  type CallProviderRouterOptions,
} from "./provider-router";

export * from "./ExpoCallKit.types";
export { CALL_EVENTS, ALL_CALL_EVENT_NAMES } from "./events";
export {
  CallKitValidationError,
  isCallEndReason,
  isUuidString,
  normalizeIncomingCallPayload,
  normalizeParticipant,
} from "./payload";
export { default as ExpoCallKitModule } from "./ExpoCallKitModule";
export * from "./provider-router";

/**
 * Reports an incoming call to the OS so the system ring UI appears.
 *
 * On iOS calls arriving via VoIP push are reported natively before JS runs —
 * use this only for calls your own signaling layer discovered. On Android
 * this is THE entry point: call it from your push handler (the module ships
 * no FCM service by design).
 *
 * @returns the OS-assigned call id.
 */
export async function reportIncomingCall(
  payload: IncomingCallPayload,
): Promise<string> {
  return ExpoCallKitModule.reportIncomingCall(
    normalizeIncomingCallPayload(payload),
  );
}

/**
 * Starts an outgoing call, showing the system in-call UI.
 *
 * @returns the OS-assigned call id.
 */
export async function startOutgoingCall(
  recipient: CallParticipant,
  options: OutgoingCallOptions = {},
): Promise<string> {
  return ExpoCallKitModule.startOutgoingCall(
    normalizeParticipant(recipient, "recipient"),
    options,
  );
}

/** Marks an outgoing call as connected (media established). */
export async function reportOutgoingCallConnected(
  callId: string,
): Promise<void> {
  return ExpoCallKitModule.reportOutgoingCallConnected(
    assertUuid(callId, "callId"),
  );
}

/**
 * Completes a pending answer. Call this after `onCallAnswered` once your
 * media layer has joined the call; on iOS this fulfills the held system
 * answer action, while Android completes the waiting Telecom callback or app
 * answer transaction. A no-op if the request already timed out.
 */
export async function answerAcknowledged(requestId: string): Promise<void> {
  return ExpoCallKitModule.answerAcknowledged(
    assertUuid(requestId, "requestId"),
  );
}

/**
 * Fails a pending answer. Call this after `onCallAnswered` when your media
 * layer could not join; the OS tears the call down.
 */
export async function answerFailed(requestId: string): Promise<void> {
  return ExpoCallKitModule.answerFailed(assertUuid(requestId, "requestId"));
}

/** Ends (or declines) a call as a local user action. */
export async function endCall(callId: string): Promise<void> {
  return ExpoCallKitModule.endCall(assertUuid(callId, "callId"));
}

/**
 * Reports that a call ended for an external reason (remote hangup, answered
 * on another device, signaling failure, ...).
 */
export async function reportCallEnded(
  callId: string,
  reason: CallEndReason,
): Promise<void> {
  if (!isCallEndReason(reason)) {
    throw new CallKitValidationError(
      `reason must be a CallEndReason, got ${JSON.stringify(reason)}`,
    );
  }
  return ExpoCallKitModule.reportCallEnded(
    assertUuid(callId, "callId"),
    reason,
  );
}

/** Sets the mute state shown by the system call UI. */
export async function setMuted(callId: string, muted: boolean): Promise<void> {
  return ExpoCallKitModule.setMuted(assertUuid(callId, "callId"), muted);
}

/** Sets the hold state of the call. */
export async function setOnHold(
  callId: string,
  onHold: boolean,
): Promise<void> {
  return ExpoCallKitModule.setOnHold(assertUuid(callId, "callId"), onHold);
}

/**
 * Returns the current OS-owned call audio route and the routes that may be
 * selected. Capability flags stay false until CallKit/Core-Telecom activates
 * call audio.
 */
export async function getAudioRouteState(): Promise<AudioRouteState> {
  return ExpoCallKitModule.getAudioRouteState();
}

/**
 * Requests one of the opaque route ids returned by `getAudioRouteState` or
 * `onAudioRouteChanged`. The call id prevents a stale in-call screen from
 * changing a newer call.
 */
export async function selectAudioRoute(
  callId: string,
  routeId: string,
): Promise<void> {
  if (typeof routeId !== "string" || routeId.trim().length === 0) {
    throw new CallKitValidationError("routeId must be a non-empty string");
  }
  return ExpoCallKitModule.selectAudioRoute(
    assertUuid(callId, "callId"),
    routeId,
  );
}

/**
 * Temporarily overrides iOS call output to the built-in speaker. Passing false
 * clears the override and lets iOS choose its normal route. Android callers
 * should select the speaker/earpiece endpoint from `availableRoutes` instead.
 */
export async function setSpeakerEnabled(
  callId: string,
  enabled: boolean,
): Promise<void> {
  return ExpoCallKitModule.setSpeakerEnabled(
    assertUuid(callId, "callId"),
    enabled,
  );
}

/** Returns the current call session, or null when idle. */
export async function getActiveCall(): Promise<CallSession | null> {
  return ExpoCallKitModule.getActiveCall();
}

/**
 * Returns the current VoIP push token synchronously, or null if none has
 * been issued yet. iOS only (Android token plumbing lives in your app).
 */
export function getVoipToken(): VoipToken | null {
  return ExpoCallKitModule.getVoipToken();
}

/**
 * Registers for VoIP pushes (iOS PushKit). Also done automatically at app
 * launch; calling again is a safe no-op. No-op on Android.
 */
export function registerVoipPushes(): void {
  ExpoCallKitModule.registerVoipPushes();
}

/**
 * Pre-heats the audio session (iOS: AVAudioSession .playAndRecord /
 * .voiceChat). Do NOT start audio I/O until `onAudioSessionActivated`.
 * No-op on Android — core-telecom owns audio focus.
 */
export function configureAudioSession(): void {
  ExpoCallKitModule.configureAudioSession();
}

/**
 * Requests runtime permissions the call UI needs: POST_NOTIFICATIONS on
 * Android 13+. Resolves immediately as granted on iOS.
 */
export async function requestPermissions(): Promise<CallKitPermissions> {
  return ExpoCallKitModule.requestPermissions();
}

/** Adds a typed listener for a call event. */
export function addCallKitListener<EventName extends keyof ExpoCallKitEvents>(
  eventName: EventName,
  listener: ExpoCallKitEvents[EventName],
): EventSubscription {
  return ExpoCallKitModule.addListener(eventName, listener);
}

/**
 * Binds one or more app-owned media/signaling providers to the native call
 * lifecycle. The returned subscription removes every listener atomically.
 *
 * Selection is fail-closed: exactly one adapter must match each incoming call
 * before the OS answer is acknowledged. No provider SDK becomes a dependency
 * of this package.
 */
export function bindCallProviderAdapters(
  adapters: readonly CallProviderAdapter[],
  options: CallProviderRouterOptions = {},
): EventSubscription {
  const router = new CallProviderRouter(adapters, options);
  const subscriptions = [
    addCallKitListener("onIncomingCall", router.onIncoming),
    addCallKitListener("onCallAnswered", (event) => {
      router.onAnswered(event, {
        acknowledge: answerAcknowledged,
        fail: answerFailed,
      });
    }),
    addCallKitListener("onCallEnded", router.onEnded),
    addCallKitListener("onMuteChanged", router.onMuteChanged),
    addCallKitListener("onHoldChanged", router.onHoldChanged),
    addCallKitListener("onDtmf", router.onDtmf),
    addCallKitListener("onAudioSessionActivated", router.onAudioActivated),
    addCallKitListener("onAudioSessionDeactivated", router.onAudioDeactivated),
  ];

  return {
    remove() {
      subscriptions.forEach((subscription) => subscription.remove());
      router.clear();
    },
  };
}
