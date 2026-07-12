# Changelog

## Unreleased

- iOS: remove the cold-start acknowledgement race by registering pending
  answers synchronously before their request ids are emitted.
- iOS: deterministically tear down failed/timed-out answers and handle CallKit's
  own action-timeout callback without touching an action after it expires.
- Tests: add a native Swift lifecycle suite and a macOS CI job.
- Docs: clarify the signaling/media ownership contract and correct the APNs
  expiration and force-quit guidance.

## 0.1.0

Initial release.

- iOS: CallKit system call UI (CXProvider/CXCallController) + in-module PushKit VoIP push handling with killed-state safety, deferred answer fulfillment, plain AVAudioSession pre-heat (no WebRTC dependency), bounded per-event queues with cold-start flush.
- Android: Jetpack `androidx.core:core-telecom` call sessions, CallStyle notifications with lock-screen full-screen intent, notification answer/decline plumbing, POST_NOTIFICATIONS runtime request, package-internal broadcast fallback for terminal events. No FCM service — the app's own push layer calls `reportIncomingCall`.
- Config plugin for Info.plist keys, entitlements, background modes, Android meta-data, and an optional call-event receiver.
