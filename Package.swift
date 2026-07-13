// swift-tools-version: 5.9

import PackageDescription

// This package exists only to run the platform-independent iOS lifecycle
// state-machine tests in CI. The published Expo module is still delivered by
// npm + CocoaPods through ios/ExpoCallKit.podspec.
let package = Package(
  name: "ExpoCallKitNativeTests",
  platforms: [.macOS(.v13)],
  products: [],
  targets: [
    .target(
      name: "ExpoCallKitPendingAnswers",
      path: "ios/PendingAnswersCore"
    ),
    .target(
      name: "ExpoCallKitCallRegistry",
      path: "ios/CallRegistryCore"
    ),
    .target(
      name: "ExpoCallKitEventPolicy",
      path: "ios/EventCore"
    ),
    .target(
      name: "ExpoCallKitPushPayload",
      path: "ios/PushPayloadCore"
    ),
    .testTarget(
      name: "ExpoCallKitPendingAnswersTests",
      dependencies: [
        "ExpoCallKitPendingAnswers",
        "ExpoCallKitCallRegistry",
        "ExpoCallKitEventPolicy",
        "ExpoCallKitPushPayload",
      ],
      path: "native-tests/ios"
    ),
  ]
)
