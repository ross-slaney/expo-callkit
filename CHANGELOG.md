# Changelog

## Unreleased

- Outgoing multi-provider calls now preserve the canonical `provider` through
  native iOS/Android sessions and the `onOutgoingCallStarted` event. The shared
  adapter router binds at call start so audio activation, mute, hold, DTMF, and
  hangup are delivered to exactly the selected media provider.

- iOS: durably restore the last valid PushKit token across cold launches while
  binding the cache to the signed build's APNs environment. Development and
  production tokens can never be restored across environments, and the old
  unscoped cache is discarded.

- Audio routing: add a typed, provider-neutral current/available route state,
  realtime `onAudioRouteChanged` updates, guarded route selection on iOS and
  Android, and an explicit iOS speaker override. Route mutations are limited to
  the OS-owned active CallKit/Core-Telecom session and fail closed when stale.

- Feedback: add `playCallFeedback`, which plays a short acknowledgement on
  private call routes and returns a haptic-only result on speaker/unknown
  routes so apps can avoid speakerphone echo.

- Treat provider missed-call/terminal VoIP pushes as dismissal of a matching
  ringing or connecting call rather than a new incoming call, while preserving
  Apple's report-per-push contract and protecting already-connected media.

- iOS: keep the call audio policy on bidirectional Bluetooth HFP and exclude
  output-only A2DP routes from the `voiceChat` session.

- iOS: normalize provider Voice SDK PushKit payloads carrying `metadata.call_id`
  while preserving the existing canonical `incomingCall` envelope.
- Events: replay the full JSON-safe native push body through incoming, answer,
  and ended events (and terminal call sessions) for cold-start media/reject
  handoff without logging signaling payloads or device tokens.
- iOS: derive a stable CallKit UUID from the provider call id so re-delivered
  pushes cannot create unrelated system-call identities.

## 0.2.0 - 2026-07-12

- Added an optional canonical `provider` key that survives native incoming-call
  and session serialization for multi-provider apps.
- Added `bindCallProviderAdapters`, a dependency-free, fail-closed router for
  ACS, Telnyx, or other app-owned signaling/media adapters. It preserves one
  adapter selection across cold-start answer/end replay and routes audio,
  mute, hold, DTMF, and teardown without bundling a carrier SDK.

- iOS: remove the cold-start acknowledgement race by registering pending
  answers synchronously before their request ids are emitted.
- iOS: deterministically tear down failed/timed-out answers and handle CallKit's
  own action-timeout callback without touching an action after it expires.
- iOS: atomically reserve the single-call slot across JS, PushKit, and outgoing
  entry points so concurrent reports cannot admit multiple calls.
- Android: enforce API 26 at build time, coalesce concurrent answer surfaces
  into one request, and use `CallControlScope.answer` for app-originated
  incoming answers.
- Android: keep Core-Telecom's system answer callback suspended until the app
  acknowledges media readiness, with deterministic failure inside a 4.5 s
  budget.
- Android: preserve one continuous CallStyle notification across incoming,
  connecting, and connected states.
- Events: keep audio activation/deactivation realtime-only on both platforms;
  stale activation events are never replayed to late JS listeners.
- Tests: add native Swift and Kotlin lifecycle suites plus native CI smoke
  builds for Expo SDK 54.
- Distribution: prepare public npm publishing with explicit package contents,
  exact peer/engine ranges, protected release gates, and provenance.
- Example: add a physical-device lifecycle harness and validate the packed
  tarball in a clean Expo SDK 54 consumer during CI.
- Docs: clarify the signaling/media ownership contract and correct the APNs
  expiration, force-quit, Android deadline, compatibility, and release
  guidance.

## 0.1.0

Initial release.

- iOS: CallKit system call UI (CXProvider/CXCallController) + in-module PushKit VoIP push handling with killed-state safety, deferred answer fulfillment, plain AVAudioSession pre-heat (no WebRTC dependency), bounded per-event queues with cold-start flush.
- Android: Jetpack `androidx.core:core-telecom` call sessions, CallStyle notifications with lock-screen full-screen intent, notification answer/decline plumbing, POST_NOTIFICATIONS runtime request, package-internal broadcast fallback for terminal events. No FCM service — the app's own push layer calls `reportIncomingCall`.
- Config plugin for Info.plist keys, entitlements, background modes, Android meta-data, and an optional call-event receiver.
