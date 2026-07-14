package dev.rossslaney.expocallkit

import android.Manifest
import android.content.Intent
import android.os.Build
import expo.modules.interfaces.permissions.PermissionsStatus
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.util.UUID

class ExpoCallKitModule : Module() {
    override fun definition() = ModuleDefinition {
        Name("ExpoCallKit")

        Events(
            CKEvents.INCOMING_CALL,
            CKEvents.CALL_ANSWERED,
            CKEvents.CALL_ENDED,
            CKEvents.OUTGOING_CALL_STARTED,
            CKEvents.MUTE_CHANGED,
            CKEvents.HOLD_CHANGED,
            CKEvents.DTMF,
            CKEvents.AUDIO_SESSION_ACTIVATED,
            CKEvents.AUDIO_SESSION_DEACTIVATED,
            CKEvents.AUDIO_ROUTE_CHANGED,
            CKEvents.VOIP_TOKEN_UPDATED,
        )

        OnCreate {
            appContext.reactContext?.let { CallEngine.initialize(it) }
            EventHub.attachSender { name, body -> sendEvent(name, body) }
            // Cold-start answer: the notification's answer action IS the
            // launch intent, so OnNewIntent never fires for it.
            consumeAnswerIntent(appContext.currentActivity?.intent)
        }

        OnDestroy {
            EventHub.attachSender(null)
        }

        OnNewIntent { intent ->
            consumeAnswerIntent(intent)
        }

        OnActivityEntersForeground {
            // Covers cold starts where the activity was not yet attached
            // during OnCreate. The action is cleared after processing, so
            // re-checks are harmless.
            consumeAnswerIntent(appContext.currentActivity?.intent)
        }

        // Per-event observation drives the replay buffers in EventHub.
        OnStartObserving(CKEvents.INCOMING_CALL) {
            EventHub.startObserving(CKEvents.INCOMING_CALL)
        }
        OnStopObserving(CKEvents.INCOMING_CALL) {
            EventHub.stopObserving(CKEvents.INCOMING_CALL)
        }

        OnStartObserving(CKEvents.CALL_ANSWERED) {
            EventHub.startObserving(CKEvents.CALL_ANSWERED)
        }
        OnStopObserving(CKEvents.CALL_ANSWERED) {
            EventHub.stopObserving(CKEvents.CALL_ANSWERED)
        }

        OnStartObserving(CKEvents.CALL_ENDED) {
            EventHub.startObserving(CKEvents.CALL_ENDED)
        }
        OnStopObserving(CKEvents.CALL_ENDED) {
            EventHub.stopObserving(CKEvents.CALL_ENDED)
        }

        OnStartObserving(CKEvents.OUTGOING_CALL_STARTED) {
            EventHub.startObserving(CKEvents.OUTGOING_CALL_STARTED)
        }
        OnStopObserving(CKEvents.OUTGOING_CALL_STARTED) {
            EventHub.stopObserving(CKEvents.OUTGOING_CALL_STARTED)
        }

        OnStartObserving(CKEvents.MUTE_CHANGED) {
            EventHub.startObserving(CKEvents.MUTE_CHANGED)
        }
        OnStopObserving(CKEvents.MUTE_CHANGED) {
            EventHub.stopObserving(CKEvents.MUTE_CHANGED)
        }

        OnStartObserving(CKEvents.HOLD_CHANGED) {
            EventHub.startObserving(CKEvents.HOLD_CHANGED)
        }
        OnStopObserving(CKEvents.HOLD_CHANGED) {
            EventHub.stopObserving(CKEvents.HOLD_CHANGED)
        }

        OnStartObserving(CKEvents.DTMF) {
            EventHub.startObserving(CKEvents.DTMF)
        }
        OnStopObserving(CKEvents.DTMF) {
            EventHub.stopObserving(CKEvents.DTMF)
        }

        OnStartObserving(CKEvents.AUDIO_SESSION_ACTIVATED) {
            EventHub.startObserving(CKEvents.AUDIO_SESSION_ACTIVATED)
        }
        OnStopObserving(CKEvents.AUDIO_SESSION_ACTIVATED) {
            EventHub.stopObserving(CKEvents.AUDIO_SESSION_ACTIVATED)
        }

        OnStartObserving(CKEvents.AUDIO_SESSION_DEACTIVATED) {
            EventHub.startObserving(CKEvents.AUDIO_SESSION_DEACTIVATED)
        }
        OnStopObserving(CKEvents.AUDIO_SESSION_DEACTIVATED) {
            EventHub.stopObserving(CKEvents.AUDIO_SESSION_DEACTIVATED)
        }

        OnStartObserving(CKEvents.AUDIO_ROUTE_CHANGED) {
            EventHub.startObserving(CKEvents.AUDIO_ROUTE_CHANGED)
        }
        OnStopObserving(CKEvents.AUDIO_ROUTE_CHANGED) {
            EventHub.stopObserving(CKEvents.AUDIO_ROUTE_CHANGED)
        }

        OnStartObserving(CKEvents.VOIP_TOKEN_UPDATED) {
            EventHub.startObserving(CKEvents.VOIP_TOKEN_UPDATED)
        }
        OnStopObserving(CKEvents.VOIP_TOKEN_UPDATED) {
            EventHub.stopObserving(CKEvents.VOIP_TOKEN_UPDATED)
        }

        // region Calls

        AsyncFunction("reportIncomingCall") { raw: Map<String, Any?> ->
            ensureInitialized()
            val payload = RingPayload.fromMap(raw) ?: throw InvalidPayloadError()
            CallEngine.reportIncomingCall(payload).toString()
        }

        AsyncFunction("startOutgoingCall") { recipient: Map<String, Any?>, options: Map<String, Any?>? ->
            ensureInitialized()
            val participant = Participant.fromMap(recipient) ?: throw InvalidPayloadError()
            @Suppress("UNCHECKED_CAST")
            CallEngine.startOutgoingCall(
                recipient = participant,
                hasVideo = options?.get("hasVideo") as? Boolean ?: false,
                metadata = options?.get("metadata") as? Map<String, Any?>,
            ).toString()
        }

        AsyncFunction("reportOutgoingCallConnected") { callId: String ->
            CallEngine.reportOutgoingConnected(parseUuid(callId))
        }

        AsyncFunction("answerAcknowledged") { requestId: String ->
            // No-op when the request already resolved (raced a timeout).
            CallEngine.acknowledgeAnswer(parseUuid(requestId))
        }

        AsyncFunction("answerFailed") { requestId: String ->
            CallEngine.rejectAnswer(parseUuid(requestId))
        }

        AsyncFunction("endCall") { callId: String ->
            CallEngine.endCall(parseUuid(callId))
        }

        AsyncFunction("reportCallEnded") { callId: String, reason: String ->
            val endReason = EndReason.fromWire(reason) ?: throw InvalidEndReasonError(reason)
            CallEngine.reportCallEnded(parseUuid(callId), endReason)
        }

        AsyncFunction("setMuted") { callId: String, muted: Boolean ->
            CallEngine.setMuted(parseUuid(callId), muted)
        }

        AsyncFunction("setOnHold") { callId: String, onHold: Boolean ->
            CallEngine.setOnHold(parseUuid(callId), onHold)
        }

        AsyncFunction("getActiveCall") {
            CallEngine.activeSessionMap()
        }

        // endregion

        // region Push / audio / permissions

        Function("getVoipToken") {
            // Push token plumbing is the app's responsibility on Android
            // (no FCM service ships in this module).
            null as Map<String, Any?>?
        }

        Function("registerVoipPushes") {
            // No-op on Android; see getVoipToken.
        }

        Function("configureAudioSession") {
            // No-op on Android; core-telecom owns audio focus.
        }

        AsyncFunction("getAudioRouteState") {
            CallEngine.audioRouteState()
        }

        AsyncFunction("selectAudioRoute") { callId: String, routeId: String, promise: Promise ->
            val parsedId = try {
                parseUuid(callId)
            } catch (error: CodedException) {
                promise.reject(error)
                return@AsyncFunction
            }

            CallEngine.selectAudioRoute(parsedId, routeId) { result ->
                result.fold(
                    onSuccess = { promise.resolve() },
                    onFailure = { error ->
                        promise.reject(
                            error as? CodedException
                                ?: AudioRouteRejectedError(error.message ?: "request failed"),
                        )
                    },
                )
            }
        }

        AsyncFunction("setSpeakerEnabled") { callId: String, enabled: Boolean ->
            CallEngine.setSpeakerEnabled(parseUuid(callId), enabled)
        }

        AsyncFunction("playCallFeedback") { callId: String ->
            CallEngine.playCallFeedback(parseUuid(callId))
        }

        AsyncFunction("requestPermissions") { promise: Promise ->
            if (Build.VERSION.SDK_INT < 33) {
                promise.resolve(mapOf("notifications" to "granted"))
                return@AsyncFunction
            }
            val permissions = appContext.permissions
            if (permissions == null) {
                promise.resolve(mapOf("notifications" to "undetermined"))
                return@AsyncFunction
            }
            permissions.askForPermissions(
                { results ->
                    val status = results[Manifest.permission.POST_NOTIFICATIONS]?.status
                    val outcome = if (status == PermissionsStatus.GRANTED) "granted" else "denied"
                    promise.resolve(mapOf("notifications" to outcome))
                },
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }

        // endregion
    }

    private fun ensureInitialized() {
        appContext.reactContext?.let { CallEngine.initialize(it) }
    }

    /** Handles the notification answer action; clears it to avoid replays. */
    private fun consumeAnswerIntent(intent: Intent?) {
        if (intent?.action != CKIntents.ACTION_ANSWER) {
            return
        }
        val id = intent.getStringExtra(CKIntents.EXTRA_CALL_ID)?.let {
            try {
                UUID.fromString(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        } ?: return

        intent.action = null
        CallEngine.handleAnswer(id)
    }

    private fun parseUuid(value: String): UUID = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw InvalidUuidError(value)
    }
}
