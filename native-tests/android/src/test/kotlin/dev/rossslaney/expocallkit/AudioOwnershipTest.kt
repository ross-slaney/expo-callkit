package dev.rossslaney.expocallkit

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOwnershipTest {
    @Test
    fun `activation is keyed to the owning call`() {
        val ownership = AudioOwnership()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        assertTrue(ownership.activate(first).changed)
        assertTrue(ownership.owns(first))
        assertFalse(ownership.owns(second))

        val transfer = ownership.activate(second)
        assertEquals(first, transfer.previous)
        assertEquals(second, transfer.current)
        assertTrue(ownership.owns(second))
    }

    @Test
    fun `stale teardown cannot deactivate the next call`() {
        val ownership = AudioOwnership()
        val ended = UUID.randomUUID()
        val active = UUID.randomUUID()

        ownership.activate(ended)
        ownership.activate(active)

        val stale = ownership.deactivate(ended)
        assertFalse(stale.changed)
        assertEquals(active, ownership.current())

        val current = ownership.deactivate(active)
        assertTrue(current.changed)
        assertNull(ownership.current())
    }

    @Test
    fun `duplicate activation is idempotent`() {
        val ownership = AudioOwnership()
        val callId = UUID.randomUUID()

        assertTrue(ownership.activate(callId).changed)
        assertFalse(ownership.activate(callId).changed)
    }
}
