package dev.rossslaney.expocallkit

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tracks answers waiting on the app's media layer, mirroring iOS.
 *
 * `register` hands out a request id surfaced through `onCallAnswered`; the
 * app resolves it with `answerAcknowledged`/`answerFailed`, and a timeout
 * fires `onTimeout` if neither arrives in time.
 */
object PendingAnswers {
    private class Entry(
        val callId: UUID,
        val timeoutJob: Job,
    )

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val entries = ConcurrentHashMap<UUID, Entry>()

    fun register(callId: UUID, timeoutMs: Long, onTimeout: (UUID) -> Unit): UUID {
        val requestId = UUID.randomUUID()
        val timeoutJob = scope.launch {
            delay(timeoutMs)
            if (entries.remove(requestId) != null) {
                onTimeout(callId)
            }
        }
        entries[requestId] = Entry(callId, timeoutJob)
        return requestId
    }

    /** Returns the call id when the request was still pending, else null. */
    fun acknowledge(requestId: UUID): UUID? = resolve(requestId)

    /** Returns the call id when the request was still pending, else null. */
    fun fail(requestId: UUID): UUID? = resolve(requestId)

    /** Drops any pending requests for a call that ended first. */
    fun abandonFor(callId: UUID) {
        entries.entries
            .filter { it.value.callId == callId }
            .forEach { (requestId, entry) ->
                if (entries.remove(requestId) != null) {
                    entry.timeoutJob.cancel()
                }
            }
    }

    private fun resolve(requestId: UUID): UUID? {
        val entry = entries.remove(requestId) ?: return null
        entry.timeoutJob.cancel()
        return entry.callId
    }
}
