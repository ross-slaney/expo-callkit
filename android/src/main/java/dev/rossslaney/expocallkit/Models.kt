package dev.rossslaney.expocallkit

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Event name constants shared with `src/events.ts` and the iOS side. */
object CKEvents {
    const val INCOMING_CALL = "onIncomingCall"
    const val CALL_ANSWERED = "onCallAnswered"
    const val CALL_ENDED = "onCallEnded"
    const val OUTGOING_CALL_STARTED = "onOutgoingCallStarted"
    const val MUTE_CHANGED = "onMuteChanged"
    const val HOLD_CHANGED = "onHoldChanged"
    const val DTMF = "onDtmf"
    const val AUDIO_SESSION_ACTIVATED = "onAudioSessionActivated"
    const val AUDIO_SESSION_DEACTIVATED = "onAudioSessionDeactivated"
    const val VOIP_TOKEN_UPDATED = "onVoipTokenUpdated"

    val ALL = listOf(
        INCOMING_CALL,
        CALL_ANSWERED,
        CALL_ENDED,
        OUTGOING_CALL_STARTED,
        MUTE_CHANGED,
        HOLD_CHANGED,
        DTMF,
        AUDIO_SESSION_ACTIVATED,
        AUDIO_SESSION_DEACTIVATED,
        VOIP_TOKEN_UPDATED,
    )
}

/** Intent actions / extras used by notifications and the broadcast fallback. */
object CKIntents {
    const val ACTION_ANSWER = "dev.rossslaney.expocallkit.ANSWER"
    const val ACTION_DECLINE = "dev.rossslaney.expocallkit.DECLINE"
    const val ACTION_CALL_EVENT = "dev.rossslaney.expocallkit.CALL_EVENT"
    const val EXTRA_CALL_ID = "dev.rossslaney.expocallkit.EXTRA_CALL_ID"
}

/** Manifest meta-data keys written by the config plugin. */
object CKConfigKeys {
    const val INCOMING_TIMEOUT = "ExpoCallKitIncomingTimeout"
    const val ANSWER_FULFILL_TIMEOUT = "ExpoCallKitAnswerFulfillTimeout"
    const val OUTGOING_TIMEOUT = "ExpoCallKitOutgoingTimeout"
    const val RINGTONE = "ExpoCallKitRingtone"
}

data class Participant(
    val id: String,
    val displayName: String? = null,
    val phoneNumber: String? = null,
    val email: String? = null,
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("id", id)
        displayName?.let { put("displayName", it) }
        phoneNumber?.let { put("phoneNumber", it) }
        email?.let { put("email", it) }
    }

    companion object {
        fun fromMap(raw: Map<String, Any?>?): Participant? {
            val id = (raw?.get("id") as? String)?.takeIf { it.isNotBlank() } ?: return null
            return Participant(
                id = id,
                displayName = (raw["displayName"] as? String)?.takeIf { it.isNotBlank() },
                phoneNumber = (raw["phoneNumber"] as? String)?.takeIf { it.isNotBlank() },
                email = (raw["email"] as? String)?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** Validated incoming call description, handed over by the app's push layer. */
data class RingPayload(
    val eventId: String,
    val serverCallId: String,
    val provider: String? = null,
    val caller: Participant,
    val hasVideo: Boolean = false,
    val metadata: Map<String, Any?>? = null,
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("eventId", eventId)
        put("serverCallId", serverCallId)
        provider?.let { put("provider", it) }
        put("caller", caller.toMap())
        put("hasVideo", hasVideo)
        metadata?.let { put("metadata", it) }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(raw: Map<String, Any?>?): RingPayload? {
            val eventId = (raw?.get("eventId") as? String)?.takeIf { it.isNotBlank() } ?: return null
            val serverCallId =
                (raw["serverCallId"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val caller = Participant.fromMap(raw["caller"] as? Map<String, Any?>) ?: return null
            return RingPayload(
                eventId = eventId,
                serverCallId = serverCallId,
                provider = (raw["provider"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                caller = caller,
                hasVideo = raw["hasVideo"] as? Boolean ?: false,
                metadata = raw["metadata"] as? Map<String, Any?>,
            )
        }
    }
}

enum class CallOrigin(val wire: String) {
    INCOMING("incoming"),
    OUTGOING("outgoing"),
}

enum class CallStatus(val wire: String) {
    RINGING("ringing"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    ENDED("ended"),
}

enum class EndReason(val wire: String) {
    FAILED("failed"),
    REMOTE_ENDED("remoteEnded"),
    UNANSWERED("unanswered"),
    ANSWERED_ELSEWHERE("answeredElsewhere"),
    DECLINED_ELSEWHERE("declinedElsewhere"),
    LOCAL("local"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): EndReason? = entries.firstOrNull { it.wire == value }
    }
}

/** In-memory state for one call session. */
data class ActiveCall(
    val id: UUID,
    val origin: CallOrigin,
    val status: CallStatus,
    val remoteParty: Participant,
    val serverCallId: String? = null,
    val provider: String? = null,
    val metadata: Map<String, Any?>? = null,
    val hasVideo: Boolean = false,
    val incomingPayload: RingPayload? = null,
    val isMuted: Boolean = false,
    val isOnHold: Boolean = false,
    val connectedAt: Instant? = null,
) {
    /** Serializes to the TS `CallSession` shape. */
    fun toSessionMap(): Map<String, Any?> = buildMap {
        put("id", id.toString())
        put("origin", origin.wire)
        put("status", status.wire)
        put("isMuted", isMuted)
        put("isOnHold", isOnHold)
        when (origin) {
            CallOrigin.INCOMING -> put("caller", remoteParty.toMap())
            CallOrigin.OUTGOING -> put("recipient", remoteParty.toMap())
        }
        serverCallId?.let { put("serverCallId", it) }
        provider?.let { put("provider", it) }
        metadata?.let { put("metadata", it) }
        connectedAt?.let { put("connectedAt", DateTimeFormatter.ISO_INSTANT.format(it)) }
    }
}
