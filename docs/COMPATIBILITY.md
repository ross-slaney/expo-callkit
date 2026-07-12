# Compatibility policy

Native compatibility is claimed only where this repository runs an actual
consumer build. A permissive package-manager install is not evidence that
CallKit, PushKit, or Core-Telecom compiles and behaves correctly.

## Certified baseline

| Layer                        | Certified value                                          |
| ---------------------------- | -------------------------------------------------------- |
| Expo                         | SDK 54 (`expo` 54.0.35 smoke consumer)                   |
| React Native                 | 0.81.4–0.81.x (smoke consumer currently resolves 0.81.5) |
| React                        | 19.1.x                                                   |
| Expo architecture            | New Architecture enabled                                 |
| Node                         | 20.19.4 or newer                                         |
| npm                          | 10.8.2 or newer                                          |
| iOS deployment target        | 15.1                                                     |
| iOS compiler validation      | Xcode 16.2, pod compiled in Swift 5.9 language mode      |
| Android min SDK              | 26; lower consumers fail manifest merge by design        |
| Android compile / target SDK | 36 / 36                                                  |
| Android toolchain            | Kotlin 2.1.20, Gradle 8.14.3 in the SDK 54 smoke app     |

`package.json` encodes the corresponding peer contract:

```json
{
  "expo": "^54.0.0",
  "react": ">=19.1.0 <19.2.0",
  "react-native": ">=0.81.4 <0.82.0"
}
```

## Not certified

- Expo SDK 55, 56, or 57. Expo 57 prebuild and CocoaPods installation were
  exercised during development, but the available Xcode 16.2 toolchain cannot
  compile Expo 57's Swift-tools 6.2 JSI dependency. That is not a compatibility
  pass or a demonstrated package failure.
- React Native's legacy architecture.
- iOS below 15.1 or Android API 24/25.
- macOS, tvOS, watchOS, visionOS, or Expo Go.
- Call media. This package coordinates native system call state; the consumer's
  ACS/WebRTC/SIP SDK and backend determine whether audio actually flows.

## Known adoption gaps

- Android process-dead incoming-call delivery is not built in. The package has
  no native FCM service; the consumer must provide a high-priority,
  headless-capable push handler before it can call `reportIncomingCall`.
- The `ringtone` option references a sound that is already in the iOS app
  bundle or Android `res/raw`. The config plugin does not copy ringtone assets
  into generated native projects yet.
- There is no bundled LiveKit, ACS, WebRTC, or SIP media adapter. This keeps the
  package provider-agnostic, but each integration must implement and test the
  media-acknowledgement and audio-session contract itself.
- CI compiles clean Expo SDK 54 consumers and exercises native lifecycle state
  machines. It does not run real PushKit/FCM calls, lock-screen/wearable flows,
  or two-device audio tests on physical hardware.

## Device proof required for a new row

Before widening a peer range, add that Expo/React Native combination to native
CI, compile both platforms, and run physical-device calls in foreground,
background, locked, and process-not-running states. Android certification also
requires notification, lock-screen, Bluetooth/wearable answer, and callback
timeout checks. iOS certification requires real PushKit delivery and CallKit
answer/audio-session checks.
