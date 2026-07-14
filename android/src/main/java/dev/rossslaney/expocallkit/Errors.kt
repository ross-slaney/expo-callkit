package dev.rossslaney.expocallkit

import expo.modules.kotlin.exception.CodedException

internal class CallExistsError :
    CodedException("ERR_CALL_EXISTS", "A call session already exists; end it before starting another", null)

internal class DuplicateRingError(eventId: String) :
    CodedException("ERR_DUPLICATE_EVENT", "Incoming call event '$eventId' was already reported", null)

internal class NoSuchCallError(id: String) :
    CodedException("ERR_NO_CALL", "No active call with id '$id'", null)

internal class InvalidUuidError(value: String) :
    CodedException("ERR_INVALID_UUID", "'$value' is not a valid UUID string", null)

internal class InvalidPayloadError :
    CodedException(
        "ERR_INVALID_PAYLOAD",
        "Incoming call payload must include eventId, serverCallId and caller.id",
        null,
    )

internal class InvalidEndReasonError(value: String) :
    CodedException("ERR_INVALID_REASON", "'$value' is not a valid CallEndReason", null)

internal class NotInitializedError :
    CodedException(
        "ERR_NOT_INITIALIZED",
        "ExpoCallKit has not been initialized yet (no Telecom registration)",
        null,
    )

internal class AudioSessionInactiveError :
    CodedException(
        "ERR_AUDIO_INACTIVE",
        "Call audio is not active; wait for onAudioSessionActivated before changing routes",
        null,
    )

internal class AudioRouteUnavailableError(routeId: String) :
    CodedException(
        "ERR_AUDIO_ROUTE_UNAVAILABLE",
        "Audio route '$routeId' is no longer available; refresh getAudioRouteState()",
        null,
    )

internal class AudioRouteRejectedError(detail: String) :
    CodedException(
        "ERR_AUDIO_ROUTE_REJECTED",
        "Telecom rejected the audio route change: $detail",
        null,
    )

internal class AudioRouteUnsupportedError(detail: String) :
    CodedException("ERR_AUDIO_ROUTE_UNSUPPORTED", detail, null)
