import AVFoundation
import Foundation

/// Owns AVAudioSession configuration for calls.
///
/// This module deliberately has no WebRTC dependency: it configures a plain
/// `AVAudioSession` and signals activation strictly through events. The app's
/// media layer (e.g. the ACS calling SDK) must not start audio I/O until
/// `onAudioSessionActivated` and must stop on `onAudioSessionDeactivated` —
/// CallKit owns the actual activation.
final class AudioSessionCoordinator {
  static let shared = AudioSessionCoordinator()

  private init() {}

  /// Pre-heats the session with call-appropriate settings. Safe to call
  /// repeatedly; invoked before every incoming report / outgoing start and
  /// exposed to JS as `configureAudioSession()`.
  func prewarm() {
    let session = AVAudioSession.sharedInstance()
    do {
      try session.setCategory(
        .playAndRecord,
        mode: .voiceChat,
        options: [.allowBluetooth, .allowBluetoothA2DP]
      )
    } catch {
      NSLog("[ExpoCallKit] Failed to configure AVAudioSession: \(error.localizedDescription)")
    }
  }

  /// CallKit activated the audio session — the app may start audio I/O now.
  func systemActivated() {
    EventHub.shared.emit(CKEvent.audioSessionActivated)
  }

  /// CallKit deactivated the audio session — the app must stop audio I/O.
  func systemDeactivated() {
    EventHub.shared.emit(CKEvent.audioSessionDeactivated)
  }
}
