# @ross-slaney/expo-callkit

Expo module for native call UX — **CallKit + PushKit** on iOS, **Jetpack core-telecom + CallStyle notifications** on Android.

This module owns the *system* side of calling only:

- System incoming/outgoing call UI (full-screen ring, lock screen, notification shade)
- Call session state (ringing → connecting → connected → ended)
- Audio-session activation signaling (events only — **no WebRTC dependency**)
- VoIP push token plumbing (iOS PushKit in-module; Android is bring-your-own-push **by design**)

Call *media* is your app's job (e.g. the Azure Communication Services calling SDK). The module tells you when to connect (`onCallAnswered`) and when audio I/O may start (`onAudioSessionActivated`); you tell it when media is up (`answerAcknowledged`, `reportOutgoingCallConnected`).

Requires a **custom dev client / EAS build** — none of this works in Expo Go, and CallKit/PushKit do not function on the iOS Simulator.

## Installation

The package is published to **GitHub Packages**, which requires authentication even for public packages. Create a classic personal access token with the `read:packages` scope ([github.com/settings/tokens](https://github.com/settings/tokens)), then add to your project's `.npmrc`:

```ini
@ross-slaney:registry=https://npm.pkg.github.com
//npm.pkg.github.com/:_authToken=${GITHUB_PACKAGES_TOKEN}
```

```sh
export GITHUB_PACKAGES_TOKEN=ghp_your_token   # or set it in CI secrets
npx expo install @ross-slaney/expo-callkit
```

Add the config plugin (autolinking links native code, but plugins are never auto-applied) and raise the Android minSdk to 26 (required by `androidx.core:core-telecom`):

```jsonc
// app.json
{
  "expo": {
    "plugins": [
      ["@ross-slaney/expo-callkit", {
        "microphonePermissionText": "Genius uses the microphone for calls",
        "incomingCallTimeout": 45,
        "answerFulfillTimeout": 30,
        "outgoingCallTimeout": 60
        // "ringtone": "ringtone.caf",                       // optional
        // "androidCallEventReceiver": "com.you.CallEvents"  // optional, see below
      }],
      ["expo-build-properties", { "android": { "minSdkVersion": 26 } }]
    ]
  }
}
```

Then rebuild the native projects: `npx expo prebuild --clean && npx expo run:ios` (or EAS Build).

### Plugin props

| Prop | Default | Effect |
| --- | --- | --- |
| `microphonePermissionText` | "Allow $(PRODUCT_NAME) to access the microphone during calls" | `NSMicrophoneUsageDescription` |
| `incomingCallTimeout` | `45` (seconds) | Ring time before auto-end as `unanswered` |
| `answerFulfillTimeout` | `30` (seconds) | Time allowed between `onCallAnswered` and `answerAcknowledged` |
| `outgoingCallTimeout` | `60` (seconds) | Unconnected outgoing call auto-end |
| `ringtone` | system default | iOS bundle sound filename / Android `res/raw` resource name |
| `androidCallEventReceiver` | — | FQCN of an app `BroadcastReceiver` for `dev.rossslaney.expocallkit.CALL_EVENT` |

The plugin also adds `UIBackgroundModes: [voip, audio]`, ensures an `aps-environment` entitlement (development default — release builds get the real value from the provisioning profile), and writes the `ExpoCallKit*` Info.plist keys / Android `meta-data`. An optional 40×40pt template asset named **`CallKitIcon`** in your asset catalog becomes the app icon inside the iOS system call UI.

## Usage

### Incoming call flow (the important one)

```tsx
import * as CallKit from "@ross-slaney/expo-callkit";
import { useEffect } from "react";

export function useCallEngine(acs: MyAcsMediaLayer) {
  useEffect(() => {
    // Mount these listeners as early as possible (app root). Events fired
    // before JS was ready (cold starts) are replayed with meta.flushed=true.
    const subs = [
      CallKit.addCallKitListener("onIncomingCall", ({ callId, payload }) => {
        // System UI is already ringing. Prepare your media layer.
        acs.prefetch(payload.serverCallId, payload.metadata);
      }),

      CallKit.addCallKitListener("onCallAnswered", async ({ callId, requestId }) => {
        // The user tapped answer; iOS holds the system action open until you
        // resolve it (or the answerFulfillTimeout fires).
        try {
          await acs.join();                        // connect media
          await CallKit.answerAcknowledged(requestId);
        } catch {
          await CallKit.answerFailed(requestId);   // OS tears the call down
        }
      }),

      CallKit.addCallKitListener("onAudioSessionActivated", () => {
        acs.startAudio();   // do NOT start audio I/O before this event (iOS)
      }),
      CallKit.addCallKitListener("onAudioSessionDeactivated", () => {
        acs.stopAudio();
      }),

      CallKit.addCallKitListener("onCallEnded", ({ session, reason }) => {
        acs.hangup(session.serverCallId, reason);
        // reason: "local" = this user ended/declined; "remoteEnded",
        // "unanswered", "answeredElsewhere", "declinedElsewhere", "failed", ...
      }),
    ];
    return () => subs.forEach((s) => s.remove());
  }, []);
}
```

Remote hangup / answered-on-another-device? Tell the module: `CallKit.reportCallEnded(callId, "remoteEnded")` (or `"answeredElsewhere"`, ...).

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
const token = CallKit.getVoipToken();      // { token, type: "apns-voip" } | null
CallKit.addCallKitListener("onVoipTokenUpdated", ({ token }) => syncToBackend(token));
```

### VoIP push payload shape (server → APNs)

Send to APNs with headers `apns-push-type: voip`, `apns-priority: 10`, `apns-topic: <bundle-id>.voip`. The body must wrap the call under a top-level `incomingCall` key:

```json
{
  "incomingCall": {
    "eventId": "3f6c8e1a-…",
    "serverCallId": "acs-call-id-from-backend",
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

Rules enforced natively (Apple requires a CallKit report per VoIP push — apps that skip it get killed and eventually lose push delivery):

- Valid payload, idle → system ring UI + `onIncomingCall`
- Unparseable payload → placeholder call reported and immediately ended (`failed`)
- Duplicate `eventId` within 120 s, or already in a call → reported and immediately ended (single-call model; the caller rings out)

### Android incoming calls: bring your own push

This module ships **no FCM service** — your app's existing push layer (expo-notifications, notifee, react-native-firebase, a data-message handler…) receives the push and calls:

```ts
const callId = await CallKit.reportIncomingCall({
  eventId: "…", serverCallId: "…",
  caller: { id: "…", displayName: "Jane Doe" },
  metadata: { bookingId: 42 },
});
```

Caveat: this requires JS to be running (headless JS / background message handler). If the process is fully dead and your push layer has no headless mode, the call cannot ring — pair with a high-priority FCM data message and a headless-capable handler. Declines that happen while no JS listener is alive are (a) replayed to JS on next launch with `meta.flushed: true` and (b) broadcast immediately as a package-internal intent (`dev.rossslaney.expocallkit.CALL_EVENT`, extras `eventName` + `payload` JSON) to the receiver you registered via `androidCallEventReceiver` — dedupe the two by `callId`.

Before the first call, request notification permission (Android 13+): `await CallKit.requestPermissions()`.

### Azure Communication Services notes

- Backend: `CommunicationIdentityClient` issues `voip`-scoped tokens; Call Automation (`CreateCall`/`AddParticipant` targeting the user's ACS identity) makes the phone ring.
- Push delivery: either `callAgent.registerPushNotifications(voipToken)` (mind the registrar TTL — re-register on every launch) or an EventGrid `IncomingCall` → Azure Function → APNs/FCM pipeline that emits the payload shape above.
- Media: use this module in "CallKit within app" style — create the ACS `CallAgent` **without** its own CallKitOptions, start ACS audio muted, and only start audio I/O on `onAudioSessionActivated`. Accept the ACS incoming call inside your `onCallAnswered` handler, then `answerAcknowledged(requestId)`.

## API reference

All ids are UUID strings. Functions reject with coded errors (`ERR_CALL_EXISTS`, `ERR_NO_CALL`, `ERR_INVALID_UUID`, `ERR_INVALID_PAYLOAD`, `ERR_INVALID_REASON`, `ERR_CALLKIT_REJECTED`, `ERR_DUPLICATE_EVENT`).

| Function | Returns | Notes |
| --- | --- | --- |
| `reportIncomingCall(payload)` | `Promise<callId>` | Ring the system UI. Android entry point for pushes |
| `startOutgoingCall(recipient, opts?)` | `Promise<callId>` | `opts: { hasVideo?, metadata? }` |
| `reportOutgoingCallConnected(callId)` | `Promise<void>` | Media established |
| `answerAcknowledged(requestId)` | `Promise<void>` | Fulfills the held answer action; no-op if timed out |
| `answerFailed(requestId)` | `Promise<void>` | Fails the answer; OS ends the call |
| `endCall(callId)` | `Promise<void>` | Local hangup/decline (`reason: "local"`) |
| `reportCallEnded(callId, reason)` | `Promise<void>` | External end (`remoteEnded`, `answeredElsewhere`, …) |
| `setMuted(callId, muted)` | `Promise<void>` | Updates system UI state; media mute is your job |
| `setOnHold(callId, onHold)` | `Promise<void>` | |
| `getActiveCall()` | `Promise<CallSession \| null>` | |
| `getVoipToken()` | `VoipToken \| null` (sync) | iOS only; Android always `null` |
| `registerVoipPushes()` | `void` | Idempotent; automatic at launch. Android no-op |
| `configureAudioSession()` | `void` | iOS AVAudioSession pre-heat. Android no-op |
| `requestPermissions()` | `Promise<{ notifications }>` | Android 13+ POST_NOTIFICATIONS; iOS `granted` |
| `addCallKitListener(name, fn)` | `EventSubscription` | Typed listener helper |

| Event | Payload (plus `meta: { flushed, timestamp }`) |
| --- | --- |
| `onIncomingCall` | `{ callId, payload: IncomingCallPayload }` |
| `onCallAnswered` | `{ callId, requestId }` |
| `onCallEnded` | `{ callId, session: CallSession, reason: CallEndReason }` |
| `onOutgoingCallStarted` | `{ callId }` |
| `onMuteChanged` | `{ callId, isMuted }` |
| `onHoldChanged` | `{ callId, isOnHold }` |
| `onDtmf` | `{ callId, digits }` (iOS system UI only) |
| `onAudioSessionActivated` | `{}` — start audio I/O now |
| `onAudioSessionDeactivated` | `{}` — stop audio I/O |
| `onVoipTokenUpdated` | `{ token: string \| null, type }` |

`onIncomingCall`, `onCallAnswered`, `onCallEnded`, `onVoipTokenUpdated` and `onAudioSessionActivated` are buffered natively (latest occurrence) and replayed with `meta.flushed: true` when JS mounts its listener — cold-started answers and killed-state declines are not lost.

## Design notes / limitations

- **Single-call model**: one call at a time (`maximumCallGroups = 1`). A second incoming call while busy rings out (`unanswered` on the caller's side); no call waiting.
- **`hasVideo` is cosmetic**: it flavors the system UI ("Video" badge / notification text). Telecom registration is audio-capability only; video media is entirely yours.
- Android `setMuted` and mute events are bookkeeping around the system UI; your media layer owns the actual microphone.
- `onDtmf` only fires on iOS (CallKit keypad). Android core-telecom has no DTMF callback.
- iOS "local" ends: user hangups via system UI and failed answers both surface as `reason: "local"` from `CXEndCallAction`.

## Device-testing checklist

Simulators cannot exercise this stack (no PushKit tokens, no CallKit UI). On real devices verify:

1. **iOS foreground ring**: `reportIncomingCall` from JS → full-screen ring → answer → `onCallAnswered` → `answerAcknowledged` → timer runs → hang up → `onCallEnded(local)`.
2. **iOS VoIP push, app killed**: send push (payload above) with the app force-quit, device locked → ring on lock screen → answer → app cold-starts → flushed `onCallAnswered` arrives → media connects within `answerFulfillTimeout`.
3. **iOS decline while killed** → next launch receives flushed `onCallEnded`.
4. **iOS invalid payload push** → brief "Unknown Caller" flash then ends; app not killed; pushes keep flowing.
5. **iOS answer-fulfill timeout**: answer but never call `answerAcknowledged` → call fails after 30 s.
6. **Token**: `onVoipTokenUpdated` fires on first launch; token reaches your backend.
7. **Android ring**: notification + full-screen lock-screen activity; answer from notification (warm + cold start), answer from lock screen, decline from both.
8. **Android killed-state decline** → manifest receiver gets the `CALL_EVENT` broadcast; next launch flushes `onCallEnded`.
9. **Android 13+ permission**: deny POST_NOTIFICATIONS → `requestPermissions()` reports `denied`; ring is silent (document to users).
10. **Both**: unanswered call auto-ends at `incomingCallTimeout`; outgoing call auto-ends at `outgoingCallTimeout`; mute/hold toggles from system UI emit events.

## Development

```sh
npm install        # runs prepare → builds module + plugin
npm test           # jest (4 platform projects)
npm run typecheck
npm run lint
```

Releases: publish a GitHub release — the `publish.yml` workflow builds, tests and publishes to GitHub Packages.

## License

MIT © Ross Slaney
