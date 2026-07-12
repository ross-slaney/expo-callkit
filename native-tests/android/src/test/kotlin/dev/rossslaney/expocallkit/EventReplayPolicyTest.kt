package dev.rossslaney.expocallkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EventReplayPolicyTest {
    @Test
    fun `only state reconstruction events are replayable`() {
        assertEquals(1, EventReplayPolicy.limits[CKEvents.INCOMING_CALL])
        assertEquals(1, EventReplayPolicy.limits[CKEvents.CALL_ANSWERED])
        assertEquals(1, EventReplayPolicy.limits[CKEvents.CALL_ENDED])
        assertEquals(1, EventReplayPolicy.limits[CKEvents.VOIP_TOKEN_UPDATED])
    }

    @Test
    fun `audio lifecycle events are never replayed`() {
        assertFalse(EventReplayPolicy.limits.containsKey(CKEvents.AUDIO_SESSION_ACTIVATED))
        assertFalse(EventReplayPolicy.limits.containsKey(CKEvents.AUDIO_SESSION_DEACTIVATED))
    }
}
