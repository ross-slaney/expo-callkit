package dev.rossslaney.expocallkit

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class AnswerOutcome {
    ACKNOWLEDGED,
    REJECTED,
    TIMED_OUT,
    CALL_ENDED,
}

internal enum class AnswerDriver {
    APP,
    TELECOM,
}

internal data class PendingAnswerAttempt(
    val callId: UUID,
    val requestId: UUID,
    val outcome: Deferred<AnswerOutcome>,
    /** True only after Telecom and native call state accepted the answer. */
    val completion: Deferred<Boolean>,
)

internal data class PendingAnswerRegistration(
    val attempt: PendingAnswerAttempt,
    val created: Boolean,
)

/**
 * Lock-protected answer registry shared by app-originated and Telecom-originated
 * answer paths.
 *
 * A call remains registered after JavaScript resolves its request until the
 * native Telecom transition completes. That closes the small window where a
 * duplicate answer could otherwise create a second request after the first
 * acknowledgement but before native state reaches `connected`.
 */
internal class PendingAnswerRegistry(
    private val scope: CoroutineScope,
    private val requestIdFactory: () -> UUID = UUID::randomUUID,
) {
    private data class Entry(
        val callId: UUID,
        val requestId: UUID,
        val outcome: CompletableDeferred<AnswerOutcome>,
        val completion: CompletableDeferred<Boolean>,
        val timeoutJob: Job,
        var preferredDriver: AnswerDriver,
        var claimedDriver: AnswerDriver? = null,
        var resolvedOutcome: AnswerOutcome? = null,
    ) {
        fun asAttempt() = PendingAnswerAttempt(callId, requestId, outcome, completion)
    }

    private val lock = Any()
    private val entriesByRequest = mutableMapOf<UUID, Entry>()
    private val entriesByCall = mutableMapOf<UUID, Entry>()

    fun registerOrGet(
        callId: UUID,
        timeoutMs: Long,
        driver: AnswerDriver,
    ): PendingAnswerRegistration {
        lateinit var timeoutJob: Job
        val registration = synchronized(lock) {
            entriesByCall[callId]?.let {
                // A system surface has a hard callback deadline and therefore
                // takes ownership unless the app path already started the
                // platform answer transaction.
                if (driver == AnswerDriver.TELECOM && it.claimedDriver == null) {
                    it.preferredDriver = AnswerDriver.TELECOM
                }
                return PendingAnswerRegistration(it.asAttempt(), created = false)
            }

            val requestId = requestIdFactory()
            val outcome = CompletableDeferred<AnswerOutcome>()
            val completion = CompletableDeferred<Boolean>()
            timeoutJob = scope.launch(start = CoroutineStart.LAZY) {
                delay(timeoutMs.coerceAtLeast(0L))
                resolve(requestId, AnswerOutcome.TIMED_OUT)
            }
            val entry = Entry(
                callId,
                requestId,
                outcome,
                completion,
                timeoutJob,
                preferredDriver = driver,
            )
            entriesByRequest[requestId] = entry
            entriesByCall[callId] = entry
            PendingAnswerRegistration(entry.asAttempt(), created = true)
        }

        // LAZY start guarantees both indexes contain the entry before a
        // zero-duration timeout can resolve it.
        timeoutJob.start()
        return registration
    }

    fun attemptForCall(callId: UUID): PendingAnswerAttempt? = synchronized(lock) {
        entriesByCall[callId]?.asAttempt()
    }

    /** Exactly one native path may drive the Telecom state transition. */
    fun claimDriver(callId: UUID, driver: AnswerDriver): Boolean = synchronized(lock) {
        val entry = entriesByCall[callId] ?: return false
        if (entry.claimedDriver != null || entry.preferredDriver != driver) {
            return false
        }
        entry.claimedDriver = driver
        true
    }

    /** Returns the call id when this request won the resolution race. */
    fun acknowledge(requestId: UUID): UUID? =
        resolve(requestId, AnswerOutcome.ACKNOWLEDGED)

    /** Returns the call id when this request won the resolution race. */
    fun fail(requestId: UUID): UUID? = resolve(requestId, AnswerOutcome.REJECTED)

    /** Used to leave margin inside Core-Telecom's five-second callback limit. */
    fun timeOut(requestId: UUID): UUID? = resolve(requestId, AnswerOutcome.TIMED_OUT)

    /**
     * Releases a successfully connected attempt and wakes duplicate waiters.
     * Returns false when teardown already won.
     */
    fun completeSuccessfully(callId: UUID): Boolean = synchronized(lock) {
        val entry = entriesByCall[callId] ?: return false
        if (entry.resolvedOutcome != AnswerOutcome.ACKNOWLEDGED) {
            return false
        }
        entriesByCall.remove(callId)
        entriesByRequest.remove(entry.requestId)
        entry.timeoutJob.cancel()
        entry.completion.complete(true)
    }

    /** Releases a call that ended and wakes both media and native waiters. */
    fun abandonFor(callId: UUID): Boolean = synchronized(lock) {
        val entry = entriesByCall.remove(callId) ?: return false
        entriesByRequest.remove(entry.requestId)
        entry.timeoutJob.cancel()
        if (entry.resolvedOutcome == null) {
            entry.resolvedOutcome = AnswerOutcome.CALL_ENDED
            entry.outcome.complete(AnswerOutcome.CALL_ENDED)
        }
        entry.completion.complete(false)
    }

    private fun resolve(requestId: UUID, outcome: AnswerOutcome): UUID? = synchronized(lock) {
        val entry = entriesByRequest.remove(requestId) ?: return null
        entry.timeoutJob.cancel()
        if (!entry.outcome.complete(outcome)) {
            return null
        }
        entry.resolvedOutcome = outcome
        entry.callId
    }
}

/** Testable form of Core-Telecom's bounded suspend-callback contract. */
internal suspend fun awaitAcknowledgedAnswer(
    attempt: PendingAnswerAttempt,
    timeoutMs: Long,
    onAcknowledged: suspend () -> Boolean,
): Boolean = withTimeoutOrNull(timeoutMs) {
    when (attempt.outcome.await()) {
        AnswerOutcome.ACKNOWLEDGED -> onAcknowledged()
        AnswerOutcome.REJECTED,
        AnswerOutcome.TIMED_OUT,
        AnswerOutcome.CALL_ENDED,
        -> false
    }
} == true

/** Production registry. Tests instantiate [PendingAnswerRegistry] directly. */
internal object PendingAnswers {
    private val registry = PendingAnswerRegistry(
        CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()),
    )

    fun registerOrGet(
        callId: UUID,
        timeoutMs: Long,
        driver: AnswerDriver,
    ): PendingAnswerRegistration = registry.registerOrGet(callId, timeoutMs, driver)

    fun attemptForCall(callId: UUID): PendingAnswerAttempt? = registry.attemptForCall(callId)

    fun claimDriver(callId: UUID, driver: AnswerDriver): Boolean =
        registry.claimDriver(callId, driver)

    fun acknowledge(requestId: UUID): UUID? = registry.acknowledge(requestId)

    fun fail(requestId: UUID): UUID? = registry.fail(requestId)

    fun timeOut(requestId: UUID): UUID? = registry.timeOut(requestId)

    fun completeSuccessfully(callId: UUID): Boolean = registry.completeSuccessfully(callId)

    fun abandonFor(callId: UUID): Boolean = registry.abandonFor(callId)
}
