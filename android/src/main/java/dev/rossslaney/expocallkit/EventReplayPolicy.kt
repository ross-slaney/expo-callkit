package dev.rossslaney.expocallkit

/** Native events that are safe to replay after JS starts observing. */
internal object EventReplayPolicy {
    /**
     * Audio activation is realtime-only. Replaying an old activation after
     * Telecom has already deactivated audio can make a late listener start
     * media against an inactive system session.
     */
    val limits: Map<String, Int> = mapOf(
        CKEvents.INCOMING_CALL to 1,
        CKEvents.CALL_ANSWERED to 1,
        CKEvents.CALL_ENDED to 1,
        CKEvents.VOIP_TOKEN_UPDATED to 1,
    )
}
