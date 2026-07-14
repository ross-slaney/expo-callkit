import Foundation

/// Native call states shared by CallKit and the platform-independent lifecycle
/// tests. A provider terminal push may dismiss only calls that have not yet
/// established media; once connected, a delayed push must not tear audio down.
enum CallStatus: String {
  case ringing
  case connecting
  case connected
  case ended

  var shouldEndForTerminalPush: Bool {
    switch self {
    case .ringing, .connecting:
      return true
    case .connected, .ended:
      return false
    }
  }
}
