# @ross-slaney/expo-callkit

Expo module for native call UX — **CallKit + PushKit** on iOS, **Jetpack core-telecom + CallStyle notifications** on Android.

This module owns the _system_ side of calling only:

- System incoming/outgoing call UI (full-screen ring, lock screen, notification shade)
- Call session state (ringing → connecting → connected → ended)
- Audio-session activation signaling (events only — **no WebRTC dependency**)
- Typed current/available audio routes for a custom in-call route picker
- VoIP push token plumbing (iOS PushKit in-module; Android is bring-your-own-push **by design**)

Call _media_ is your app's job (e.g. the Azure Communication Services calling SDK). The module tells you when to connect (`onCallAnswered`) and when audio I/O may start (`onAudioSessionActivated`); you tell it when media is up (`answerAcknowledged`, `reportOutgoingCallConnected`).

Multi-provider apps do not need parallel CallKit integrations. The package
includes a provider-neutral adapter router that selects exactly one app-owned
media adapter per call and forwards the complete lifecycle to it. Carrier SDKs
remain dependencies of the app, never this package.

> **This package is CallKit/Telecom plumbing, not a phone service or media
> stack.** Installing it does not provision a phone number, route calls, send
> VoIP pushes, or carry audio. Your backend/signaling provider must deliver the
> incoming call, and your app's media SDK must join it. Calling
> `answerAcknowledged` without a connected media session only produces a
> system UI that says “connected” while the user hears silence.

Requires a **custom dev client / EAS build** — none of this works in Expo Go, and CallKit/PushKit do not function on the iOS Simulator.

### Compatibility

| Surface             | Validated baseline                   | Minimum                                            |
| ------------------- | ------------------------------------ | -------------------------------------------------- |
| Expo / React Native | Expo SDK 54 / React Native 0.81      | Other Expo SDKs are not yet certified in native CI |
| iOS                 | Xcode 16.2, Swift 5.9 module mode    | iOS 15.1                                           |
| Android             | compile/target SDK 36, Kotlin 2.1.20 | API 26 (Android 8.0)                               |
| Build tooling       | Node 20.19.4, npm 10.8.2+            | Declared by `package.json#engines`                 |

The Android AAR enforces API 26. A consuming app configured below 26 fails its
manifest merge at build time instead of shipping a device-specific
`CallsManager` crash. The encoded peer ranges describe package resolution, not
a compatibility promise; expand this table only after native smoke builds are
added for another Expo SDK.

See [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) for the exact peer ranges,
architecture coverage, and the current native-push, ringtone-asset,
media-adapter, and physical-device E2E gaps.

## Installation

Public releases install from npm without a GitHub Packages token:

```sh
npx expo install @ross-slaney/expo-callkit expo-build-properties
```

Version 0.2.0 is the first release prepared for the public npm registry; 0.1.0
was GitHub Packages-only.

Add the config plugin (autolinking links native code, but plugins are never auto-applied) and raise the Android minSdk to 26 (required and enforced by `androidx.core:core-telecom`):

```jsonc
// app.json
{
  "expo": {
    "plugins": [
      [
        "@ross-slaney/expo-callkit",
        {
          "microphonePermissionText": "Genius uses the microphone for calls",
          "incomingCallTimeout": 45,
          "answerFulfillTimeout": 30,
          "outgoingCallTimeout": 60
          // "ringtone": "ringtone.caf",                       // optional
          // "androidCallEventReceiver": "com.you.CallEvents"  // optional, see below
        }
      ],
      ["expo-build-properties", { "android": { "minSdkVersion": 26 } }]
    ]
  }
}
```

Then rebuild the native projects: `npx expo prebuild --clean && npx expo run:ios` (or EAS Build).

### Plugin props

| Prop                       | Default                                                       | Effect                                                                                                               |
| -------------------------- | ------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| `microphonePermissionText` | "Allow $(PRODUCT_NAME) to access the microphone during calls" | `NSMicrophoneUsageDescription`                                                                                       |
| `incomingCallTimeout`      | `45` (seconds)                                                | Ring time before auto-end as `unanswered`                                                                            |
| `answerFulfillTimeout`     | `30` (seconds)                                                | Time allowed between `onCallAnswered` and `answerAcknowledged`; Android system-surface callbacks are capped at 4.5 s |
| `outgoingCallTimeout`      | `60` (seconds)                                                | Unconnected outgoing call auto-end                                                                                   |
| `ringtone`                 | system default                                                | Name of a sound already bundled in iOS / Android `res/raw`; the plugin does not copy the asset yet                   |
| `androidCallEventReceiver` | —                                                             | FQCN of an app `BroadcastReceiver` for `dev.rossslaney.expocallkit.CALL_EVENT`                                       |

The plugin also adds `UIBackgroundModes: [voip, audio]`, ensures an `aps-environment` entitlement (development default — release builds get the real value from the provisioning profile), and writes the `ExpoCallKit*` Info.plist keys / Android `meta-data`. An optional 40×40pt template asset named **`CallKitIcon`** in your asset catalog becomes the app icon inside the iOS system call UI.

## Usage

### Incoming call flow (the important one)

```tsx
import * as CallKit from "@ross-slaney/expo-callkit";
import { useEffect } from "react";

export function useCallEngine(acs: MyAcsMediaLayer) {
  useEffect(() => {
    // Mount these listeners as early as possible (app root). Replay-safe call
    // state events fired before JS was ready use meta.flushed=true.
    const subs = [
      CallKit.addCallKitListener("onIncomingCall", ({ callId, payload, rawPushPayload }) => {
        // System UI is already ringing. Prepare your media layer.
        // Provider SDKs that reconnect from their own push body receive the
        // complete JSON-safe payload here; never print or persist it.
        acs.prefetch(payload.serverCallId, payload.metadata, rawPushPayload);
      }),

      CallKit.addCallKitListener(
        "onCallAnswered",
        async ({ callId, requestId, payload, rawPushPayload }) => {
          // The user tapped answer. Establish the remote media session first,
          // but keep audio I/O stopped until onAudioSessionActivated below.
          // iOS holds the system action open. Android system surfaces (wearable,
          // Bluetooth, Auto) give the app less than five seconds to finish.
          try {
            await acs.join({
              serverCallId: payload?.serverCallId,
              rawPushPayload,
              startAudio: false,
            }); // signaling/media is connected
            await CallKit.answerAcknowledged(requestId);
          } catch {
            await CallKit.answerFailed(requestId); // native + system call tear down
          }
        }
      ),

      CallKit.addCallKitListener("onAudioSessionActivated", () => {
        acs.startAudio(); // do NOT start audio I/O before this event
      }),
      CallKit.addCallKitListener("onAudioSessionDeactivated", () => {
        acs.stopAudio();
      }),

      CallKit.addCallKitListener("onCallEnded", ({ session, reason, rawPushPayload }) => {
        // rawPushPayload is also preserved on session.rawPushPayload. This is
        // essential when a killed-state decline must reconnect provider
        // signaling before sending its reject/end action.
        acs.hangup(session.serverCallId, reason, rawPushPayload);
        // reason: "local" = this user ended/declined; "remoteEnded",
        // "unanswered", "answeredElsewhere", "declinedElsewhere", "failed", ...
      }),
    ];
    return () => subs.forEach((s) => s.remove());
  }, []);
}
```

`answerAcknowledged(requestId)` means “the call can genuinely proceed,” not
“the app received the event.” Call it only after the media/signaling layer has
joined successfully. If no media implementation is installed, connection
fails, or the requested call no longer exists, call `answerFailed(requestId)`.
The module then fails the pending CallKit answer and deterministically ends its
native call session.

Remote hangup / answered-on-another-device? Tell the module: `CallKit.reportCallEnded(callId, "remoteEnded")` (or `"answeredElsewhere"`, ...).

### Custom in-call audio route picker

Route state is provider-neutral and owned by the OS. Mount the realtime listener
at the app root, query once when your in-call screen mounts, and only enable the
picker when `supportsRouteSelection` is true:

```tsx
const [routeState, setRouteState] = useState<CallKit.AudioRouteState>();

useEffect(() => {
  void CallKit.getAudioRouteState().then(setRouteState);
  const subscription = CallKit.addCallKitListener(
    "onAudioRouteChanged",
    setRouteState,
  );
  return () => subscription.remove();
}, []);

async function chooseRoute(route: CallKit.AudioRoute) {
  if (!routeState?.callId || !routeState.supportsRouteSelection) return;
  await CallKit.selectAudioRoute(routeState.callId, route.id);
}
```

The route ids are opaque and short-lived; never persist them or construct your
own. On iOS, `availableRoutes` is the exact `AVAudioSession.availableInputs`
set plus a package-provided speaker choice. Choosing an input uses
`setPreferredInput`; choosing speaker uses the temporary output override.
`setSpeakerEnabled(callId, false)` only clears that override and lets iOS choose
its normal route—it does not promise the receiver when a headset is connected.
On Android, routes are the exact Core-Telecom endpoints and selection passes the
matching object back to `requestEndpointChange`; the package never synthesizes
an endpoint. `supportsSpeakerOverride` is therefore false on Android—choose the
speaker or earpiece entry with `selectAudioRoute` instead.

Route mutations fail closed before the OS activates call audio, after the call
changes, or if a device disappears between rendering and tapping. Handle the
coded error and refresh `getAudioRouteState()` rather than guessing a fallback.
`onAudioRouteChanged` is deliberately realtime-only; stale route snapshots are
never replayed after the system deactivates audio.

### ACS + Telnyx (or any multi-provider app)

Canonical backend pushes should include a stable `provider` key. The native
session preserves it across incoming, answered, and ended events, including a
killed-state replay:

```json
{
  "incomingCall": {
    "eventId": "3f6c8e1a-…",
    "serverCallId": "provider-call-id",
    "provider": "acs",
    "caller": { "id": "caller-id", "displayName": "Jane" }
  }
}
```

Bind both app-owned SDK adapters once at the app root. The router fails closed
when zero or multiple adapters match, waits for preparation, acknowledges the
native answer only after `answerIncoming` resolves, and routes audio, mute,
hold, DTMF, and cold-start teardown back to the same adapter:

```ts
const binding = CallKit.bindCallProviderAdapters([
  {
    id: "acs",
    prepareIncoming: (call) => acs.prefetch(call.serverCallId),
    answerIncoming: (call) => acs.answer(call.serverCallId),
    endCall: (call, reason) => acs.end(call.serverCallId, reason),
    activateAudio: () => acs.startAudio(),
    deactivateAudio: () => acs.stopAudio(),
    setMuted: (_call, muted) => acs.setMuted(muted),
    setOnHold: (_call, held) => acs.setHeld(held),
  },
  {
    id: "telnyx",
    matches: ({ provider, rawPushPayload }) =>
      provider === "telnyx" || isTelnyxPush(rawPushPayload),
    prepareIncoming: (call) => telnyx.prepare(call.rawPushPayload),
    answerIncoming: (call) => telnyx.answer(call.rawPushPayload),
    endCall: (call, reason) => telnyx.end(call.rawPushPayload, reason),
    activateAudio: () => telnyx.startAudio(),
    deactivateAudio: () => telnyx.stopAudio(),
    setMuted: (_call, muted) => telnyx.setMuted(muted),
    setOnHold: (_call, held) => telnyx.setHeld(held),
    sendDtmf: (_call, digits) => telnyx.sendDtmf(digits),
  },
], {
  // Send sanitized diagnostics to your own observability boundary. Never log
  // the context or rawPushPayload.
  onError: ({ phase, adapterId }) => reportCallFailure({ phase, adapterId }),
});

// On app-root teardown only:
binding.remove();
```

An explicit canonical `provider` selects the same adapter `id` and is
authoritative. `matches` is an app-defined fallback only for direct carrier
pushes that cannot include the canonical key. The router never imports,
identifies, or initializes either SDK.

### Outgoing calls

```ts
const callId = await CallKit.startOutgoingCall(
  { id: "acs:8:abc", displayName: "Jane Doe", phoneNumber: "+15550100" },
  { metadata: { bookingId: 42 } }
);
// ... connect media via your calling SDK, then:
await CallKit.reportOutgoingCallConnected(callId);
```

### VoIP push tokens (iOS)

PushKit registration happens automatically at app launch (killed-state safety). Read/sync the token:

```ts
const token = CallKit.getVoipToken(); // { token, type: "apns-voip" } | null
CallKit.addCallKitListener("onVoipTokenUpdated", ({ token }) =>
  syncToBackend(token)
);
```

### VoIP push payload shapes (server/provider → APNs)

Send to APNs with headers `apns-push-type: voip`, `apns-priority: 10`, `apns-expiration: 0`, `apns-topic: <bundle-id>.voip`. The zero expiration prevents a stale ring from being delivered after the call is already gone. A backend-owned payload wraps the call under a top-level `incomingCall` key:

```json
{
  "incomingCall": {
    "eventId": "3f6c8e1a-…",
    "serverCallId": "acs-call-id-from-backend",
    "provider": "acs",
    "caller": {
      "id": "8:acs:…",
      "displayName": "Jane from Acme Salon",
      "phoneNumber": "+15550100"
    },
    "hasVideo": false,
    "metadata": { "bookingId": 42 }
  }
}
```

Provider-owned Voice SDK pushes using a top-level `metadata` object are also
normalized natively when they include `call_id`. The commonly used
`caller_name`, `caller_number`, `event_id`, and `has_video` fields populate the
same `IncomingCallPayload`; unknown metadata is preserved. A UUID `call_id`
becomes the CallKit UUID, while opaque ids receive a deterministic UUID so
duplicate deliveries identify the same system call.

Provider terminal pushes (including Telnyx's `message: "Missed call!"` shape)
are never presented as a new incoming ring. The module closes a matching stale
ring and reports an immediately-ended watchdog call to satisfy PushKit's
report-per-delivery contract. A late terminal push never tears down an already
connected call.

Every native iOS push event exposes its full JSON-safe body as
`rawPushPayload` on `onIncomingCall`, `onCallAnswered`, and `onCallEnded`, and
on the terminal `CallSession`. State events retain it in the native replay
buffer, so an app cold-started by answer or decline can give the exact body to
its media SDK. Treat it as sensitive signaling material: pass it directly to
the SDK, never log it, persist it, or send it to analytics.

Rules enforced natively (Apple requires a CallKit report per VoIP push — apps that skip it get killed and eventually lose push delivery):

- Valid payload, idle → system ring UI + `onIncomingCall`
- Unparseable payload → placeholder call reported and immediately ended (`failed`)
- Duplicate `eventId` within 120 s, or already in a call → reported and immediately ended (single-call model; the caller rings out)

### Android incoming calls: bring your own push

This module ships **no FCM service** — your app's existing push layer (expo-notifications, notifee, react-native-firebase, a data-message handler…) receives the push and calls:

```ts
const callId = await CallKit.reportIncomingCall({
  eventId: "…",
  serverCallId: "…",
  caller: { id: "…", displayName: "Jane Doe" },
  metadata: { bookingId: 42 },
});
```

Caveat: this requires JS to be running (headless JS / background message handler). If the process is fully dead and your push layer has no headless mode, the call cannot ring — pair with a high-priority FCM data message and a headless-capable handler. Declines that happen while no JS listener is alive are (a) replayed to JS on next launch with `meta.flushed: true` and (b) broadcast immediately as a package-internal intent (`dev.rossslaney.expocallkit.CALL_EVENT`, extras `eventName` + `payload` JSON) to the receiver you registered via `androidCallEventReceiver` — dedupe the two by `callId`.

Before the first call, request notification permission (Android 13+): `await CallKit.requestPermissions()`.

Answering from the app's notification or lock-screen UI waits for
`answerAcknowledged`, then calls `CallControlScope.answer(...)`; the call is
marked connected only if Telecom accepts that operation. Answers initiated by
a wearable, Bluetooth device, Android Auto, or another system surface run
inside Core-Telecom's five-second suspend callback. This module reserves the
last 500 ms for native cleanup, so your handler has at most **4.5 seconds** to
join media and call `answerAcknowledged`. `answerFulfillTimeout` cannot extend
that platform deadline. If media is not ready, call `answerFailed`; the module
throws from the callback and tears the call down deterministically.

During this work the same notification id transitions directly from incoming
CallStyle → connecting CallStyle → ongoing CallStyle. Do not cancel or replace
the package notification from application code; Core-Telecom keeps foreground
execution priority only while a valid CallStyle remains posted.

### Azure Communication Services notes

- Backend: `CommunicationIdentityClient` issues `voip`-scoped tokens; Call Automation (`CreateCall`/`AddParticipant` targeting the user's ACS identity) makes the phone ring.
- Push delivery: either `callAgent.registerPushNotifications(voipToken)` (mind the registrar TTL — re-register on every launch) or an EventGrid `IncomingCall` → Azure Function → APNs/FCM pipeline that emits the payload shape above.
- Media: use this module in "CallKit within app" style — create the ACS `CallAgent` **without** its own CallKitOptions, start ACS audio muted, and only start audio I/O on `onAudioSessionActivated`. Accept the ACS incoming call inside your `onCallAnswered` handler, then `answerAcknowledged(requestId)`.

## API reference

All call ids are UUID strings. Functions reject with coded errors (`ERR_CALL_EXISTS`, `ERR_NO_CALL`, `ERR_INVALID_UUID`, `ERR_INVALID_PAYLOAD`, `ERR_INVALID_REASON`, `ERR_CALLKIT_REJECTED`, `ERR_DUPLICATE_EVENT`, `ERR_AUDIO_INACTIVE`, `ERR_AUDIO_ROUTE_UNAVAILABLE`, `ERR_AUDIO_ROUTE_REJECTED`, `ERR_AUDIO_ROUTE_UNSUPPORTED`).

| Function                              | Returns                        | Notes                                                |
| ------------------------------------- | ------------------------------ | ---------------------------------------------------- |
| `reportIncomingCall(payload)`         | `Promise<callId>`              | Ring the system UI. Android entry point for pushes   |
| `startOutgoingCall(recipient, opts?)` | `Promise<callId>`              | `opts: { hasVideo?, metadata? }`                     |
| `reportOutgoingCallConnected(callId)` | `Promise<void>`                | Media established                                    |
| `answerAcknowledged(requestId)`       | `Promise<void>`                | Fulfills the held answer action; no-op if timed out  |
| `answerFailed(requestId)`             | `Promise<void>`                | Fails the answer; OS ends the call                   |
| `endCall(callId)`                     | `Promise<void>`                | Local hangup/decline (`reason: "local"`)             |
| `reportCallEnded(callId, reason)`     | `Promise<void>`                | External end (`remoteEnded`, `answeredElsewhere`, …) |
| `setMuted(callId, muted)`             | `Promise<void>`                | Updates system UI state; media mute is your job      |
| `setOnHold(callId, onHold)`           | `Promise<void>`                |                                                      |
| `getAudioRouteState()`                | `Promise<AudioRouteState>`     | Read-only; unsupported capabilities are false        |
| `selectAudioRoute(callId, routeId)`   | `Promise<void>`                | Select an id from the latest available routes         |
| `setSpeakerEnabled(callId, enabled)`  | `Promise<void>`                | iOS override; Android fails as unsupported            |
| `getActiveCall()`                     | `Promise<CallSession \| null>` |                                                      |
| `getVoipToken()`                      | `VoipToken \| null` (sync)     | iOS only; Android always `null`                      |
| `registerVoipPushes()`                | `void`                         | Idempotent; automatic at launch. Android no-op       |
| `configureAudioSession()`             | `void`                         | iOS AVAudioSession pre-heat. Android no-op           |
| `requestPermissions()`                | `Promise<{ notifications }>`   | Android 13+ POST_NOTIFICATIONS; iOS `granted`        |
| `addCallKitListener(name, fn)`        | `EventSubscription`            | Typed listener helper                                |
| `bindCallProviderAdapters(adapters)`  | `EventSubscription`            | Fail-closed multi-provider media lifecycle router    |

| Event                       | Payload (plus `meta: { flushed, timestamp }`)             |
| --------------------------- | --------------------------------------------------------- |
| `onIncomingCall`            | `{ callId, payload, rawPushPayload? }`                    |
| `onCallAnswered`            | `{ callId, requestId, payload?, rawPushPayload? }`        |
| `onCallEnded`               | `{ callId, session, reason, rawPushPayload? }`            |
| `onOutgoingCallStarted`     | `{ callId }`                                              |
| `onMuteChanged`             | `{ callId, isMuted }`                                     |
| `onHoldChanged`             | `{ callId, isOnHold }`                                    |
| `onDtmf`                    | `{ callId, digits }` (iOS system UI only)                 |
| `onAudioSessionActivated`   | `{}` — start audio I/O now                                |
| `onAudioSessionDeactivated` | `{}` — stop audio I/O                                     |
| `onAudioRouteChanged`       | `AudioRouteState` — current route/capabilities            |
| `onVoipTokenUpdated`        | `{ token: string \| null, type }`                         |

`onIncomingCall`, `onCallAnswered`, `onCallEnded`, and `onVoipTokenUpdated` are
buffered natively (latest occurrence) and replayed with `meta.flushed: true`
when JS mounts its listener — cold-started answers and killed-state declines
are not lost. Audio activation/deactivation and route-change events are
realtime-only: replaying old audio state after the system deactivated audio
could incorrectly restart media or display a disconnected route, so mount the
audio listeners at the app root before accepting a call.

## Design notes / limitations

- **Single-call model**: one call at a time (`maximumCallGroups = 1`). A second incoming call while busy rings out (`unanswered` on the caller's side); no call waiting.
- **`hasVideo` is cosmetic**: it flavors the system UI ("Video" badge / notification text). Telecom registration is audio-capability only; video media is entirely yours.
- Android `setMuted` and mute events are bookkeeping around the system UI; your media layer owns the actual microphone.
- Android Core-Telecom answer callbacks wait for the existing media acknowledgement contract. Hold/active/disconnect callbacks still emit state for your media layer; this package does not pretend those events prove transport-level mute, resume, or shutdown.
- `onDtmf` only fires on iOS (CallKit keypad). Android core-telecom has no DTMF callback.
- iOS "local" ends: user hangups via system UI and failed answers both surface as `reason: "local"` from `CXEndCallAction`.

## Device-testing checklist

Simulators cannot exercise this stack (no PushKit tokens, no CallKit UI). On real devices verify:

1. **iOS foreground ring**: `reportIncomingCall` from JS → full-screen ring → answer → `onCallAnswered` → `answerAcknowledged` → timer runs → hang up → `onCallEnded(local)`.
2. **iOS VoIP push, process not running**: terminate the process without user force-quitting the app, lock the device, then send the push → ring on lock screen → answer → app cold-starts → flushed `onCallAnswered` arrives → media connects within `answerFulfillTimeout`. iOS does not deliver remote notifications after the user force-quits from the app switcher until the user launches the app again, so force-quit is not a valid receive-call test.
3. **iOS decline while killed** → next launch receives flushed `onCallEnded`.
4. **iOS invalid payload push** → brief "Unknown Caller" flash then ends; app not killed; pushes keep flowing.
5. **iOS answer-fulfill timeout**: answer but never call `answerAcknowledged` → call fails after 30 s.
6. **Token**: `onVoipTokenUpdated` fires on first launch; token reaches your backend.
7. **Android ring**: notification + full-screen lock-screen activity; answer from notification (warm + cold start), answer from lock screen, decline from both. Confirm the notification changes to “Connecting…” without disappearing, then starts its chronometer only after media acknowledgement.
8. **Android killed-state decline** → manifest receiver gets the `CALL_EVENT` broadcast; next launch flushes `onCallEnded`.
9. **Android 13+ permission**: deny POST_NOTIFICATIONS → `requestPermissions()` reports `denied`; ring is silent (document to users).
10. **Both**: unanswered call auto-ends at `incomingCallTimeout`; outgoing call auto-ends at `outgoingCallTimeout`; mute/hold toggles from system UI emit events.
11. **Android remote surface**: answer from a paired Bluetooth device, wearable, or Android Auto; media acknowledgement within 4.5 s connects, while rejection/no acknowledgement fails before Core-Telecom's 5 s deadline.
12. **Both route pickers**: while connected, switch earpiece/speaker, attach and remove a wired or Bluetooth device, and confirm `currentRoute` follows the OS. Repeat a tap as the device disconnects and confirm the request fails without selecting a fabricated fallback.

## Development

```sh
npm install        # runs prepare → builds module + plugin
npm test           # jest (4 platform projects)
npm run typecheck
npm run lint
node scripts/validate-package.mjs
swift test         # iOS lifecycle state machine
gradle --project-dir native-tests/android test
```

The [`example/`](example/) app is a physical-device lifecycle harness with a
deliberately failing media adapter. CI installs the packed tarball into an Expo
SDK 54 consumer, typechecks that example, prebuilds Android, and compiles the
native module.

Releases are gated by an exact `v<package.version>` GitHub release, the protected
`npm` environment, an `NPM_TOKEN`, package-content validation, a duplicate
version check, and npm provenance. See [docs/RELEASING.md](docs/RELEASING.md).

## License

MIT © Ross Slaney
