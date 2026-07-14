package dev.rossslaney.expocallkit

import java.util.UUID

/** Atomic ownership for the package's single OS-managed call audio session. */
internal data class AudioOwnershipChange(
    val previous: UUID?,
    val current: UUID?,
) {
    val changed: Boolean get() = previous != current
}

/**
 * Keeps audio activation correlated with a call id rather than a process-wide
 * boolean. This prevents an ending call from deactivating (or donating a stale
 * active flag to) the call that immediately follows it.
 */
internal class AudioOwnership {
    private val lock = Any()
    private var owner: UUID? = null

    fun activate(callId: UUID): AudioOwnershipChange = synchronized(lock) {
        val previous = owner
        owner = callId
        AudioOwnershipChange(previous, owner)
    }

    fun deactivate(callId: UUID): AudioOwnershipChange = synchronized(lock) {
        val previous = owner
        if (previous == callId) {
            owner = null
        }
        AudioOwnershipChange(previous, owner)
    }

    fun owns(callId: UUID): Boolean = synchronized(lock) {
        owner == callId
    }

    fun current(): UUID? = synchronized(lock) { owner }
}
