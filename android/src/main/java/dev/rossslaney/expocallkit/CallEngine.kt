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
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull

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
    // Core-Telecom cancels client callbacks at 5,000 ms. Keep margin for
    // bookkeeping and exception propagation after the media acknowledgement.
    private const val TELECOM_CALLBACK_BUDGET_MS = 4_500L

    private data class AnswerCommand(
        val callType: Int,
        val result: CompletableDeferred<CallControlResult>,
    )

    private data class EndpointCommand(
        val endpoint: CallEndpointCompat,
        val result: CompletableDeferred<CallControlResult>,
    )

    private class Lane {
        val answer = Channel<AnswerCommand>(Channel.BUFFERED)
        val setActive = Channel<Unit>(Channel.CONFLATED)
        val setInactive = Channel<Unit>(Channel.CONFLATED)
        val disconnect = Channel<DisconnectCause>(Channel.CONFLATED)
        val endpoint = Channel<EndpointCommand>(Channel.BUFFERED)
    }

    private class Controller(val lane: Lane) {
        var job: Job? = null
        var ringTimeout: Job? = null

        @Volatile
        var currentEndpoint: CallEndpointCompat? = null

        @Volatile
        var availableEndpoints: List<CallEndpointCompat> = emptyList()
    }

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val calls = ConcurrentHashMap<UUID, ActiveCall>()
    private val controllers = ConcurrentHashMap<UUID, Controller>()
    private val recentEventIds = ConcurrentHashMap<String, Long>()
    private val answerLock = Any()

    private val sessionsFlow = MutableStateFlow<Map<UUID, ActiveCall>>(emptyMap())

    /** Live view of all call sessions, consumed by [IncomingCallActivity]. */
    val sessions: StateFlow<Map<UUID, ActiveCall>> get() = sessionsFlow

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var callsManager: CallsManager? = null

    private val audioOwnership = AudioOwnership()

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
                provider = payload.provider,
                metadata = payload.metadata,
                hasVideo = payload.hasVideo,
                incomingPayload = payload,
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

        controller.job = runTelecomSession(
            id,
            attributes,
            controller.lane,
            onAnswer = { callType -> handleTelecomAnswer(id, callType) },
        ) {
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
        val registration = beginAnswer(id, AnswerDriver.APP) ?: return
        if (!registration.created) {
            return
        }
        mainScope.launch(CoroutineName("ExpoCallKit-AppAnswer-$id")) {
            driveAppAnswer(registration.attempt)
        }
    }

    /** Signals that the app's existing media layer is ready to proceed. */
    fun acknowledgeAnswer(requestId: UUID) {
        PendingAnswers.acknowledge(requestId)
    }

    /** Signals that the app's media layer could not establish the call. */
    fun rejectAnswer(requestId: UUID) {
        PendingAnswers.fail(requestId)
    }

    /**
     * Creates exactly one pending answer for all app/system answer surfaces.
     * The winning transition also swaps the incoming notification directly to
     * a same-id connecting CallStyle, so foreground priority never has a gap.
     */
    private fun beginAnswer(
        id: UUID,
        driver: AnswerDriver,
    ): PendingAnswerRegistration? {
        var remoteName: String? = null
        val registration = synchronized(answerLock) {
            val call = calls[id] ?: return null

            PendingAnswers.attemptForCall(id)?.let {
                return PendingAnswers.registerOrGet(id, answerFulfillTimeoutMs, driver)
            }

            if (call.origin != CallOrigin.INCOMING || call.status != CallStatus.RINGING) {
                return null
            }

            val created = PendingAnswers.registerOrGet(
                id,
                answerFulfillTimeoutMs,
                driver,
            )
            calls[id] = call.copy(status = CallStatus.CONNECTING)
            remoteName = call.remoteParty.displayName
            publish()
            cancelRingTimeout(id)
            created
        }

        scheduleAnswerStarted(registration.attempt, remoteName)
        return registration
    }

    /**
     * Notification IPC and bridge delivery run after the caller suspends, so
     * they cannot consume Core-Telecom's callback before deadline accounting
     * starts. The lock/recheck keeps end-before-delivery ordering deterministic.
     */
    private fun scheduleAnswerStarted(attempt: PendingAnswerAttempt, remoteName: String?) {
        mainScope.launch(CoroutineName("ExpoCallKit-AnswerStarted-${attempt.callId}")) {
            synchronized(answerLock) {
                val current = calls[attempt.callId]
                val pending = PendingAnswers.attemptForCall(attempt.callId)
                if (
                    current?.status != CallStatus.CONNECTING ||
                    pending?.requestId != attempt.requestId
                ) {
                    return@synchronized
                }

                appContext?.let {
                    CallNotifications.showConnecting(it, attempt.callId, remoteName)
                }
                EventHub.emit(
                    CKEvents.CALL_ANSWERED,
                    buildMap {
                        put("callId", attempt.callId.toString())
                        put("requestId", attempt.requestId.toString())
                        current.incomingPayload?.let { put("payload", it.toMap()) }
                    },
                )
            }
        }
    }

    /** App UI answer: media acknowledgement, then `CallControlScope.answer`. */
    private suspend fun driveAppAnswer(attempt: PendingAnswerAttempt) {
        when (attempt.outcome.await()) {
            AnswerOutcome.ACKNOWLEDGED -> {
                if (!PendingAnswers.claimDriver(attempt.callId, AnswerDriver.APP)) {
                    // A Core-Telecom callback promoted itself to owner and is
                    // waiting on the same acknowledgement.
                    return
                }

                val accepted = requestTelecomAnswer(
                    attempt.callId,
                    CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                )
                if (!accepted || !markAnswerConnected(attempt.callId)) {
                    finalize(attempt.callId, EndReason.FAILED)
                }
            }

            AnswerOutcome.REJECTED,
            AnswerOutcome.TIMED_OUT,
            -> finalize(attempt.callId, EndReason.FAILED)

            AnswerOutcome.CALL_ENDED -> Unit
        }
    }

    /**
     * Remote/system answer: the suspend callback does not return until the
     * app has acknowledged real media readiness. Failure throws so Telecom
     * tears down the transaction instead of treating it as successful.
     */
    private suspend fun handleTelecomAnswer(id: UUID, @Suppress("UNUSED_PARAMETER") callType: Int) {
        val deadline = SystemClock.elapsedRealtime() + TELECOM_CALLBACK_BUDGET_MS
        val registration = beginAnswer(id, AnswerDriver.TELECOM)
            ?: throw IllegalStateException("Call $id is not answerable")
        val attempt = registration.attempt

        try {
            val remainingMs = deadline - SystemClock.elapsedRealtime()
            val accepted = if (remainingMs > 0) {
                awaitAcknowledgedAnswer(attempt, remainingMs) {
                    if (PendingAnswers.claimDriver(id, AnswerDriver.TELECOM)) {
                        markAnswerConnected(id)
                    } else {
                        // The app path had already started scope.answer().
                        attempt.completion.await()
                    }
                }
            } else {
                false
            }

            if (accepted != true) {
                PendingAnswers.timeOut(attempt.requestId)
                finalize(id, EndReason.FAILED, sendDisconnect = false)
                throw IllegalStateException("Call $id was not answered within Telecom's deadline")
            }
        } catch (error: CancellationException) {
            PendingAnswers.timeOut(attempt.requestId)
            finalize(id, EndReason.FAILED, sendDisconnect = false)
            throw error
        }
    }

    private suspend fun requestTelecomAnswer(id: UUID, callType: Int): Boolean {
        val lane = controllers[id]?.lane ?: return false
        val result = CompletableDeferred<CallControlResult>()
        if (lane.answer.trySend(AnswerCommand(callType, result)).isFailure) {
            return false
        }
        return try {
            withTimeoutOrNull(TELECOM_CALLBACK_BUDGET_MS) {
                result.await() is CallControlResult.Success
            } == true
        } catch (_: Exception) {
            false
        }
    }

    /** Marks connected only after media and Telecom have both accepted. */
    private fun markAnswerConnected(id: UUID): Boolean = synchronized(answerLock) {
        val call = calls[id]?.takeIf { it.status == CallStatus.CONNECTING } ?: return false
        if (!PendingAnswers.completeSuccessfully(id)) {
            return false
        }

        val now = Instant.now()
        calls[id] = call.copy(status = CallStatus.CONNECTED, connectedAt = now)
        publish()
        appContext?.let {
            CallNotifications.showOngoing(
                it,
                id,
                call.remoteParty.displayName,
                now.toEpochMilli(),
            )
        }
        activateAudio(id)
        true
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

        controller.job = runTelecomSession(id, attributes, controller.lane, onAnswer = { _ -> }) {
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
        val existing = synchronized(answerLock) {
            val call = calls[id] ?: return
            // Clear and announce this call's audio ownership before freeing the
            // single-call slot. A following call can never receive a delayed
            // deactivation event from this teardown.
            deactivateAudio(id)
            calls.remove(id)
            PendingAnswers.abandonFor(id)
            call
        }
        publish()

        cancelRingTimeout(id)

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

    fun audioRouteState(): Map<String, Any?> = synchronized(answerLock) {
        val callId = calls.keys.firstOrNull()
        val controller = callId?.let { controllers[it] }
        val active = callId != null && audioOwnership.owns(callId) && controller != null
        val routes = if (active) {
            controller.availableEndpoints.map(::endpointToMap).distinctBy { it["id"] }
        } else {
            emptyList()
        }

        return mapOf(
            "callId" to callId?.toString(),
            "isAudioActive" to active,
            "currentRoute" to if (active) controller.currentEndpoint?.let(::endpointToMap) else null,
            "availableRoutes" to routes,
            "supportsRouteSelection" to (active && routes.isNotEmpty()),
            // Core-Telecom exposes endpoint selection, not an output override.
            "supportsSpeakerOverride" to false,
        )
    }

    fun selectAudioRoute(
        callId: UUID,
        routeId: String,
        completion: (Result<Unit>) -> Unit,
    ) {
        mainScope.launch {
            completion(runCatching { performAudioRouteSelection(callId, routeId) })
        }
    }

    private suspend fun performAudioRouteSelection(callId: UUID, routeId: String) {
        calls[callId] ?: throw NoSuchCallError(callId.toString())
        if (!audioOwnership.owns(callId)) {
            throw AudioSessionInactiveError()
        }
        val controller = controllers[callId] ?: throw AudioSessionInactiveError()
        val endpoint = controller.availableEndpoints.firstOrNull {
            endpointRouteId(it) == routeId
        } ?: throw AudioRouteUnavailableError(routeId)

        val result = CompletableDeferred<CallControlResult>()
        if (controller.lane.endpoint.trySend(EndpointCommand(endpoint, result)).isFailure) {
            throw AudioRouteRejectedError("the active Telecom session is no longer accepting commands")
        }

        val outcome = try {
            withTimeoutOrNull(TELECOM_CALLBACK_BUDGET_MS) { result.await() }
        } catch (error: Exception) {
            throw AudioRouteRejectedError(error.message ?: "request failed")
        } ?: throw AudioRouteRejectedError("request timed out")

        if (outcome is CallControlResult.Error) {
            throw AudioRouteRejectedError("error code ${outcome.errorCode}")
        }
    }

    fun setSpeakerEnabled(callId: UUID, enabled: Boolean) {
        calls[callId] ?: throw NoSuchCallError(callId.toString())
        throw AudioRouteUnsupportedError(
            "Android has no speaker override; select ${if (enabled) "the speaker" else "a non-speaker"} " +
                "route returned by getAudioRouteState()",
        )
    }

    // endregion

    // region Telecom plumbing

    private fun runTelecomSession(
        id: UUID,
        attributes: CallAttributesCompat,
        lane: Lane,
        onAnswer: suspend (Int) -> Unit,
        onReady: () -> Unit,
    ): Job = mainScope.launch(CoroutineName("ExpoCallKit-$id")) {
        try {
            requireManager().addCall(
                attributes,
                onAnswer = { callType -> onAnswer(callType) },
                onDisconnect = { finalize(id, EndReason.REMOTE_ENDED, sendDisconnect = false) },
                onSetActive = { applyHold(id, false) },
                onSetInactive = { applyHold(id, true) },
            ) {
                val scope: CallControlScope = this
                launch { pumpLane(id, lane, scope) }
                launch { scope.isMuted.collect { muted -> applySystemMute(id, muted) } }
                launch {
                    scope.currentCallEndpoint.collect { endpoint ->
                        controllers[id]?.currentEndpoint = endpoint
                        emitAudioRouteChanged()
                    }
                }
                launch {
                    scope.availableEndpoints.collect { endpoints ->
                        controllers[id]?.availableEndpoints = endpoints.toList()
                        emitAudioRouteChanged()
                    }
                }
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
                lane.answer.onReceive { command ->
                    try {
                        command.result.complete(scope.answer(command.callType))
                    } catch (error: Exception) {
                        command.result.completeExceptionally(error)
                    }
                    Unit
                }
                lane.setActive.onReceive {
                    val result = scope.setActive()
                    if (result is CallControlResult.Error) {
                        Log.e(TAG, "setActive failed for $id (${result.errorCode}); ending call")
                        finalize(id, EndReason.FAILED)
                    } else {
                        // Outgoing call audio is not active merely because
                        // addCall created a scope. Telecom owns activation and
                        // must accept setActive before media/routes are exposed.
                        activateAudio(id)
                    }
                }
                lane.setInactive.onReceive {
                    val result = scope.setInactive()
                    if (result is CallControlResult.Error) {
                        Log.w(TAG, "setInactive failed for $id (${result.errorCode})")
                    }
                }
                lane.disconnect.onReceive { cause -> scope.disconnect(cause) }
                lane.endpoint.onReceive { command ->
                    try {
                        command.result.complete(scope.requestEndpointChange(command.endpoint))
                    } catch (error: Exception) {
                        command.result.completeExceptionally(error)
                    }
                    Unit
                }
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

    private fun activateAudio(id: UUID) = synchronized(answerLock) {
        if (calls[id] == null) {
            return@synchronized
        }
        val change = audioOwnership.activate(id)
        if (!change.changed) {
            return@synchronized
        }
        if (change.previous != null) {
            EventHub.emit(CKEvents.AUDIO_SESSION_DEACTIVATED)
        }
        EventHub.emit(CKEvents.AUDIO_SESSION_ACTIVATED)
        emitAudioRouteChanged()
    }

    private fun deactivateAudio(id: UUID) = synchronized(answerLock) {
        val change = audioOwnership.deactivate(id)
        if (!change.changed) {
            return@synchronized
        }
        EventHub.emit(CKEvents.AUDIO_SESSION_DEACTIVATED)
        EventHub.emit(CKEvents.AUDIO_ROUTE_CHANGED, audioRouteState())
    }

    private fun emitAudioRouteChanged() {
        if (audioOwnership.current() != null) {
            EventHub.emit(CKEvents.AUDIO_ROUTE_CHANGED, audioRouteState())
        }
    }

    private fun endpointRouteId(endpoint: CallEndpointCompat): String =
        "android:endpoint:${endpoint.identifier.uuid}"

    private fun endpointToMap(endpoint: CallEndpointCompat): Map<String, Any> = mapOf(
        "id" to endpointRouteId(endpoint),
        "name" to endpoint.name.toString(),
        "type" to when (endpoint.type) {
            CallEndpointCompat.TYPE_EARPIECE -> "earpiece"
            CallEndpointCompat.TYPE_SPEAKER -> "speaker"
            CallEndpointCompat.TYPE_BLUETOOTH -> "bluetooth"
            CallEndpointCompat.TYPE_WIRED_HEADSET -> "wiredHeadset"
            CallEndpointCompat.TYPE_STREAMING -> "streaming"
            else -> "unknown"
        },
    )

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
