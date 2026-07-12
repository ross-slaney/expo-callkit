package dev.rossslaney.expocallkit

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Android call lifecycle manager built on Jetpack core-telecom.
 *
 * `CallsManager.addCall` hands the app a `CallControlScope` whose methods are
 * only valid inside the addCall block, so external mutations are funneled
 * through conflated channels ("lanes") drained by a single select loop per
 * call. State lives in a ConcurrentHashMap and is mirrored to [sessions] for
 * the lock-screen activity.
 *
 * This module deliberately ships no FCM service: the app's own push layer
 * calls [reportIncomingCall] from whatever context it runs in.
 */
object CallEngine {
    private const val TAG = "ExpoCallKit"
    private const val DEDUPE_WINDOW_MS = 120_000L

    private class Lane {
        val setActive = Channel<Unit>(Channel.CONFLATED)
        val setInactive = Channel<Unit>(Channel.CONFLATED)
        val disconnect = Channel<DisconnectCause>(Channel.CONFLATED)
    }

    private class Controller(val lane: Lane) {
        var job: Job? = null
        var ringTimeout: Job? = null
    }

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val calls = ConcurrentHashMap<UUID, ActiveCall>()
    private val controllers = ConcurrentHashMap<UUID, Controller>()
    private val recentEventIds = ConcurrentHashMap<String, Long>()

    private val sessionsFlow = MutableStateFlow<Map<UUID, ActiveCall>>(emptyMap())

    /** Live view of all call sessions, consumed by [IncomingCallActivity]. */
    val sessions: StateFlow<Map<UUID, ActiveCall>> get() = sessionsFlow

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var callsManager: CallsManager? = null

    @Volatile
    private var audioActive = false

    private var incomingTimeoutMs = 45_000L
    private var answerFulfillTimeoutMs = 30_000L
    private var outgoingTimeoutMs = 60_000L

    /**
     * Registers the app with Telecom and prepares notification channels.
     * Safe to call repeatedly; runs at module OnCreate.
     */
    @Synchronized
    fun initialize(context: Context) {
        if (callsManager != null) {
            return
        }
        val app = context.applicationContext
        appContext = app

        val manager = CallsManager(app)
        manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
        callsManager = manager

        incomingTimeoutMs = readTimeoutMs(app, CKConfigKeys.INCOMING_TIMEOUT, incomingTimeoutMs)
        answerFulfillTimeoutMs =
            readTimeoutMs(app, CKConfigKeys.ANSWER_FULFILL_TIMEOUT, answerFulfillTimeoutMs)
        outgoingTimeoutMs = readTimeoutMs(app, CKConfigKeys.OUTGOING_TIMEOUT, outgoingTimeoutMs)

        CallNotifications.ensureChannels(app)
        EventHub.enableBroadcasts(app)
        Log.d(TAG, "CallEngine initialized")
    }

    // region Incoming

    /**
     * Reports an incoming call to Telecom and shows the ring notification.
     * Deduplicates by `eventId` within a 120s window; a redelivery of the
     * call currently ringing returns the existing call id.
     */
    fun reportIncomingCall(payload: RingPayload): UUID {
        requireManager()
        val app = requireContext()

        calls.values.firstOrNull()?.let { existing ->
            if (existing.serverCallId == payload.serverCallId && existing.status != CallStatus.ENDED) {
                return existing.id
            }
            throw CallExistsError()
        }

        pruneEventIds()
        if (recentEventIds.putIfAbsent(payload.eventId, SystemClock.elapsedRealtime()) != null) {
            throw DuplicateRingError(payload.eventId)
        }

        val id = UUID.randomUUID()
        putCall(
            ActiveCall(
                id = id,
                origin = CallOrigin.INCOMING,
                status = CallStatus.RINGING,
                remoteParty = payload.caller,
                serverCallId = payload.serverCallId,
                metadata = payload.metadata,
                hasVideo = payload.hasVideo,
            )
        )

        val controller = Controller(Lane())
        controllers[id] = controller

        CallNotifications.showIncoming(app, id, payload.caller.displayName, payload.hasVideo)

        val attributes = CallAttributesCompat(
            displayName = payload.caller.displayName ?: "Unknown",
            address = participantUri(payload.caller),
            direction = CallAttributesCompat.DIRECTION_INCOMING,
            callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )

        controller.job = runTelecomSession(id, attributes, controller.lane, onAnswer = { handleAnswer(id) }) {
            EventHub.emit(
                CKEvents.INCOMING_CALL,
                mapOf("callId" to id.toString(), "payload" to payload.toMap()),
            )
            startRingTimeout(id, incomingTimeoutMs)
        }

        return id
    }

    /**
     * Shared answer entry point: Telecom's onAnswer callback, the
     * notification answer action, and the lock-screen activity all land here.
     * Emits `onCallAnswered` with a pending request id; the call connects
     * when the app acknowledges.
     */
    fun handleAnswer(id: UUID) {
        val call = calls[id] ?: return
        if (call.status == CallStatus.CONNECTED || call.status == CallStatus.ENDED) {
            return
        }
        cancelRingTimeout(id)
        update(id) { it.copy(status = CallStatus.CONNECTING) }
        activateAudio()

        val requestId = PendingAnswers.register(id, answerFulfillTimeoutMs) { callId ->
            Log.w(TAG, "Answer fulfillment timed out for $callId")
            finalize(callId, EndReason.FAILED)
        }
        EventHub.emit(
            CKEvents.CALL_ANSWERED,
            mapOf("callId" to id.toString(), "requestId" to requestId.toString()),
        )
    }

    /** Completes a pending answer: connect the call and go active in Telecom. */
    fun acknowledgeAnswer(requestId: UUID) {
        val callId = PendingAnswers.acknowledge(requestId) ?: return
        val now = Instant.now()
        val updated = update(callId) { it.copy(status = CallStatus.CONNECTED, connectedAt = now) }
        controllers[callId]?.lane?.setActive?.trySend(Unit)

        val app = appContext
        if (app != null && updated != null) {
            CallNotifications.showOngoing(
                app,
                callId,
                updated.remoteParty.displayName,
                now.toEpochMilli(),
            )
        }
    }

    /** Fails a pending answer: the call is torn down as failed. */
    fun rejectAnswer(requestId: UUID) {
        val callId = PendingAnswers.fail(requestId) ?: return
        finalize(callId, EndReason.FAILED)
    }

    // endregion

    // region Outgoing

    fun startOutgoingCall(
        recipient: Participant,
        hasVideo: Boolean,
        metadata: Map<String, Any?>?,
    ): UUID {
        requireManager()
        val app = requireContext()
        if (calls.isNotEmpty()) {
            throw CallExistsError()
        }

        val id = UUID.randomUUID()
        putCall(
            ActiveCall(
                id = id,
                origin = CallOrigin.OUTGOING,
                status = CallStatus.CONNECTING,
                remoteParty = recipient,
                metadata = metadata,
                hasVideo = hasVideo,
            )
        )

        val controller = Controller(Lane())
        controllers[id] = controller

        val attributes = CallAttributesCompat(
            displayName = recipient.displayName ?: "Unknown",
            address = participantUri(recipient),
            direction = CallAttributesCompat.DIRECTION_OUTGOING,
            callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )

        controller.job = runTelecomSession(id, attributes, controller.lane, onAnswer = {}) {
            activateAudio()
            CallNotifications.showOutgoing(app, id, recipient.displayName)
            EventHub.emit(CKEvents.OUTGOING_CALL_STARTED, mapOf("callId" to id.toString()))
            startRingTimeout(id, outgoingTimeoutMs)
        }

        return id
    }

    fun reportOutgoingConnected(id: UUID) {
        calls[id] ?: throw NoSuchCallError(id.toString())
        cancelRingTimeout(id)
        val now = Instant.now()
        val updated = update(id) { it.copy(status = CallStatus.CONNECTED, connectedAt = now) }
        controllers[id]?.lane?.setActive?.trySend(Unit)

        val app = appContext
        if (app != null && updated != null) {
            CallNotifications.showOngoing(app, id, updated.remoteParty.displayName, now.toEpochMilli())
        }
    }

    // endregion

    // region Ending

    /** Local end/decline (hang-up button, notification decline, JS endCall). */
    fun endCall(id: UUID) {
        calls[id] ?: throw NoSuchCallError(id.toString())
        finalize(id, EndReason.LOCAL)
    }

    /** Externally-caused end (remote hangup, answered elsewhere, ...). */
    fun reportCallEnded(id: UUID, reason: EndReason) {
        calls[id] ?: throw NoSuchCallError(id.toString())
        finalize(id, reason)
    }

    /**
     * Shared teardown. Idempotent — the first caller wins. Sends the mapped
     * DisconnectCause into the Telecom scope (letting addCall return
     * naturally so Telecom receives the cause), emits `onCallEnded`, and
     * releases audio after the last call.
     */
    private fun finalize(id: UUID, reason: EndReason, sendDisconnect: Boolean = true) {
        val existing = calls.remove(id) ?: return
        publish()

        cancelRingTimeout(id)
        PendingAnswers.abandonFor(id)

        val controller = controllers.remove(id)
        if (sendDisconnect) {
            controller?.lane?.disconnect?.trySend(disconnectCauseFor(reason))
        }

        appContext?.let { CallNotifications.dismiss(it) }

        val ended = existing.copy(status = CallStatus.ENDED)
        EventHub.emit(
            CKEvents.CALL_ENDED,
            mapOf(
                "callId" to id.toString(),
                "session" to ended.toSessionMap(),
                "reason" to reason.wire,
            ),
        )

        if (calls.isEmpty()) {
            deactivateAudio()
        }
    }

    private fun disconnectCauseFor(reason: EndReason): DisconnectCause = when (reason) {
        EndReason.REMOTE_ENDED,
        EndReason.ANSWERED_ELSEWHERE,
        EndReason.DECLINED_ELSEWHERE,
        -> DisconnectCause(DisconnectCause.REMOTE)

        EndReason.UNANSWERED -> DisconnectCause(DisconnectCause.MISSED)

        EndReason.LOCAL -> DisconnectCause(DisconnectCause.LOCAL)

        EndReason.FAILED,
        EndReason.UNKNOWN,
        -> DisconnectCause(DisconnectCause.ERROR)
    }

    // endregion

    // region Mute / hold

    /**
     * App-initiated mute. Only bookkeeping + event — the app's media layer
     * owns the actual microphone track.
     */
    fun setMuted(id: UUID, muted: Boolean) {
        val call = calls[id] ?: throw NoSuchCallError(id.toString())
        if (call.isMuted == muted) {
            return
        }
        update(id) { it.copy(isMuted = muted) }
        EventHub.emit(
            CKEvents.MUTE_CHANGED,
            mapOf("callId" to id.toString(), "isMuted" to muted),
        )
    }

    fun setOnHold(id: UUID, onHold: Boolean) {
        val call = calls[id] ?: throw NoSuchCallError(id.toString())
        if (onHold) {
            controllers[id]?.lane?.setInactive?.trySend(Unit)
        } else if (call.status == CallStatus.CONNECTED) {
            controllers[id]?.lane?.setActive?.trySend(Unit)
        }
        applyHold(id, onHold)
    }

    private fun applyHold(id: UUID, onHold: Boolean) {
        val call = calls[id] ?: return
        if (call.isOnHold == onHold) {
            return
        }
        update(id) { it.copy(isOnHold = onHold) }
        EventHub.emit(
            CKEvents.HOLD_CHANGED,
            mapOf("callId" to id.toString(), "isOnHold" to onHold),
        )
    }

    private fun applySystemMute(id: UUID, muted: Boolean) {
        val call = calls[id] ?: return
        if (call.isMuted == muted) {
            return
        }
        update(id) { it.copy(isMuted = muted) }
        EventHub.emit(
            CKEvents.MUTE_CHANGED,
            mapOf("callId" to id.toString(), "isMuted" to muted),
        )
    }

    // endregion

    // region Queries

    fun activeSessionMap(): Map<String, Any?>? = calls.values.firstOrNull()?.toSessionMap()

    // endregion

    // region Telecom plumbing

    private fun runTelecomSession(
        id: UUID,
        attributes: CallAttributesCompat,
        lane: Lane,
        onAnswer: () -> Unit,
        onReady: () -> Unit,
    ): Job = mainScope.launch(CoroutineName("ExpoCallKit-$id")) {
        try {
            requireManager().addCall(
                attributes,
                onAnswer = { onAnswer() },
                onDisconnect = { finalize(id, EndReason.REMOTE_ENDED, sendDisconnect = false) },
                onSetActive = { applyHold(id, false) },
                onSetInactive = { applyHold(id, true) },
            ) {
                val scope: CallControlScope = this
                launch { pumpLane(id, lane, scope) }
                launch { scope.isMuted.collect { muted -> applySystemMute(id, muted) } }
                // Collected to keep the scope alive; endpoint routing is left
                // to the system UI in this version.
                launch { scope.currentCallEndpoint.collect {} }
                onReady()
            }
        } catch (_: CancellationException) {
            // Process-level teardown; the finally block sweeps up.
        } catch (e: Exception) {
            Log.e(TAG, "Telecom addCall failed for $id: ${e.message}")
            finalize(id, EndReason.FAILED, sendDisconnect = false)
        } finally {
            controllers.remove(id)
            if (calls.containsKey(id)) {
                // addCall returned without a normal teardown path.
                finalize(id, EndReason.FAILED, sendDisconnect = false)
            }
        }
    }

    /** Serializes external mutations into the CallControlScope. */
    private suspend fun pumpLane(id: UUID, lane: Lane, scope: CallControlScope) {
        while (currentCoroutineContext().isActive) {
            select<Unit> {
                lane.setActive.onReceive {
                    val result = scope.setActive()
                    if (result is CallControlResult.Error) {
                        Log.e(TAG, "setActive failed for $id (${result.errorCode}); ending call")
                        finalize(id, EndReason.FAILED)
                    }
                }
                lane.setInactive.onReceive {
                    val result = scope.setInactive()
                    if (result is CallControlResult.Error) {
                        Log.w(TAG, "setInactive failed for $id (${result.errorCode})")
                    }
                }
                lane.disconnect.onReceive { cause -> scope.disconnect(cause) }
            }
        }
    }

    private fun participantUri(participant: Participant): Uri {
        participant.phoneNumber?.let {
            return Uri.fromParts(PhoneAccount.SCHEME_TEL, it, null)
        }
        participant.email?.let {
            return Uri.fromParts(PhoneAccount.SCHEME_SIP, it, null)
        }
        return Uri.fromParts(PhoneAccount.SCHEME_SIP, "${participant.id}@expo-callkit.invalid", null)
    }

    // endregion

    // region Timeouts / audio / state helpers

    private fun startRingTimeout(id: UUID, timeoutMs: Long) {
        cancelRingTimeout(id)
        val job = mainScope.launch {
            delay(timeoutMs)
            val call = calls[id] ?: return@launch
            if (call.status != CallStatus.CONNECTED) {
                finalize(id, EndReason.UNANSWERED)
            }
        }
        controllers[id]?.ringTimeout = job
    }

    private fun cancelRingTimeout(id: UUID) {
        controllers[id]?.let {
            it.ringTimeout?.cancel()
            it.ringTimeout = null
        }
    }

    private fun activateAudio() {
        if (audioActive) {
            return
        }
        audioActive = true
        EventHub.emit(CKEvents.AUDIO_SESSION_ACTIVATED)
    }

    private fun deactivateAudio() {
        if (!audioActive) {
            return
        }
        audioActive = false
        EventHub.emit(CKEvents.AUDIO_SESSION_DEACTIVATED)
    }

    private fun putCall(call: ActiveCall) {
        calls[call.id] = call
        publish()
    }

    private fun update(id: UUID, transform: (ActiveCall) -> ActiveCall): ActiveCall? {
        val updated = calls.computeIfPresent(id) { _, current -> transform(current) }
        publish()
        return updated
    }

    private fun publish() {
        sessionsFlow.value = calls.toMap()
    }

    private fun pruneEventIds() {
        val now = SystemClock.elapsedRealtime()
        recentEventIds.entries.removeIf { now - it.value > DEDUPE_WINDOW_MS }
    }

    private fun requireManager(): CallsManager = callsManager ?: throw NotInitializedError()

    private fun requireContext(): Context = appContext ?: throw NotInitializedError()

    private fun readTimeoutMs(context: Context, key: String, defaultMs: Long): Long = try {
        val info = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )
        val seconds = info.metaData?.getInt(key, 0) ?: 0
        if (seconds > 0) seconds * 1000L else defaultMs
    } catch (_: Throwable) {
        defaultMs
    }

    // endregion
}
