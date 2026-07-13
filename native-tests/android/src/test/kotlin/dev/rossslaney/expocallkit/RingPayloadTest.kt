package dev.rossslaney.expocallkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingPayloadTest {
    @Test
    fun `provider key survives native payload and session round trip`() {
        val payload = RingPayload.fromMap(
            mapOf(
                "eventId" to "event-1",
                "serverCallId" to "server-1",
                "provider" to "telnyx",
                "caller" to mapOf("id" to "caller-1"),
            )
        )!!

        assertEquals("telnyx", payload.provider)
        assertEquals("telnyx", payload.toMap()["provider"])

        val session = ActiveCall(
            id = java.util.UUID.randomUUID(),
            origin = CallOrigin.INCOMING,
            status = CallStatus.RINGING,
            remoteParty = payload.caller,
            serverCallId = payload.serverCallId,
            provider = payload.provider,
        )
        assertEquals("telnyx", session.toSessionMap()["provider"])
    }

    @Test
    fun `legacy payload remains provider optional`() {
        val payload = RingPayload.fromMap(
            mapOf(
                "eventId" to "event-1",
                "serverCallId" to "server-1",
                "caller" to mapOf("id" to "caller-1"),
            )
        )!!

        assertNull(payload.provider)
        assertNull(payload.toMap()["provider"])
    }
}
