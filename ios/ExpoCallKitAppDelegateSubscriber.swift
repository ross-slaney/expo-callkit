import ExpoModulesCore

/// Runs at `didFinishLaunching`, before React Native boots.
///
/// Ordering matters for killed-state VoIP pushes: the event hub (whose
/// replay buffers are configured synchronously in its initializer), the call
/// center (CXProvider), and the PushKit registry must all exist before the
/// system delivers the push that launched the app.
public final class ExpoCallKitAppDelegateSubscriber: ExpoAppDelegateSubscriber {
  public func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    _ = EventHub.shared
    _ = CallCenter.shared
    _ = AudioSessionCoordinator.shared
    VoipPushCoordinator.shared.register()
    return true
  }
}
