# Expo CallKit example

This is a physical-device lifecycle harness, not a fake phone service. It can
report a foreground test call and exercise the package's native UI, event
delivery, answer failure, and teardown paths.

The `media` adapter in `App.tsx` deliberately rejects `join()`. Replace it with
your real ACS, WebRTC, or SIP implementation before expecting audio. Keep that
implementation's audio stopped until `onAudioSessionActivated`; call
`answerAcknowledged` only after signaling and media have joined successfully.
The harness also renders the package's typed audio-route state. Route controls
remain disabled until the OS activates call audio; with a real media adapter,
use them to verify speaker, receiver, wired, and Bluetooth changes on-device.

```sh
npm install
npm run ios       # or npm run android
```

Expo Go is unsupported. iOS CallKit/PushKit requires a physical device and a
custom development build. Android system-surface behavior should also be
verified on a physical device with a Bluetooth or wearable answer path.
