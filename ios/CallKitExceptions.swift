import ExpoModulesCore

/// Thrown when a second call session would violate the single-call model.
internal final class CallExistsException: Exception {
  override var code: String {
    "ERR_CALL_EXISTS"
  }

  override var reason: String {
    "A call session already exists; end it before starting another"
  }
}

/// Thrown when a callId/requestId argument is not a valid UUID string.
internal final class InvalidUuidException: GenericException<String> {
  override var code: String {
    "ERR_INVALID_UUID"
  }

  override var reason: String {
    "'\(param)' is not a valid UUID string"
  }
}

/// Thrown when the referenced call does not exist (already ended, or never
/// existed).
internal final class NoSuchCallException: GenericException<String> {
  override var code: String {
    "ERR_NO_CALL"
  }

  override var reason: String {
    "No active call with id '\(param)'"
  }
}

/// Thrown when an end reason string is not a member of `CallEndReason`.
internal final class InvalidEndReasonException: GenericException<String> {
  override var code: String {
    "ERR_INVALID_REASON"
  }

  override var reason: String {
    "'\(param)' is not a valid CallEndReason"
  }
}

/// Thrown when CallKit rejects a report or transaction (Do Not Disturb,
/// blocked caller, duplicate UUID, transaction failure, ...).
internal final class CallKitRejectedException: GenericException<String> {
  override var code: String {
    "ERR_CALLKIT_REJECTED"
  }

  override var reason: String {
    "CallKit rejected the request: \(param)"
  }
}

/// Thrown when an incoming call payload fails native validation.
internal final class InvalidPayloadException: Exception {
  override var code: String {
    "ERR_INVALID_PAYLOAD"
  }

  override var reason: String {
    "Incoming call payload must include eventId, serverCallId and caller.id"
  }
}

/// Thrown when route mutation is attempted outside CallKit's active audio
/// session. The module never activates AVAudioSession independently.
internal final class AudioSessionInactiveException: Exception {
  override var code: String {
    "ERR_AUDIO_INACTIVE"
  }

  override var reason: String {
    "Call audio is not active; wait for onAudioSessionActivated before changing routes"
  }
}

internal final class AudioRouteUnavailableException: GenericException<String> {
  override var code: String {
    "ERR_AUDIO_ROUTE_UNAVAILABLE"
  }

  override var reason: String {
    "Audio route '\(param)' is no longer available; refresh getAudioRouteState()"
  }
}

internal final class AudioRouteRejectedException: GenericException<String> {
  override var code: String {
    "ERR_AUDIO_ROUTE_REJECTED"
  }

  override var reason: String {
    "The system rejected the audio route change: \(param)"
  }
}
