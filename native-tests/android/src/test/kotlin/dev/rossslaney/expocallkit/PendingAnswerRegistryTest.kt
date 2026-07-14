package dev.rossslaney.expocallkit

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingAnswerRegistryTest {
    @Test
    fun concurrentAnswersCreateOneRequest() = runTest {
        val registry = PendingAnswerRegistry(this)
        val callId = UUID.randomUUID()

        val registrations = (0 until 100).map {
            async(Dispatchers.Default) {
                registry.registerOrGet(callId, 30_000, AnswerDriver.APP)
            }
        }.awaitAll()

        assertEquals(1, registrations.count { it.created })
        assertEquals(1, registrations.map { it.attempt.requestId }.distinct().size)
        registrations.forEach {
            assertSame(registrations.first().attempt.outcome, it.attempt.outcome)
        }

        assertTrue(registry.abandonFor(callId))
    }

    @Test
    fun acknowledgementWinsOnceAndStaysRegisteredUntilNativeCompletion() = runTest {
        val registry = PendingAnswerRegistry(this)
        val callId = UUID.randomUUID()
        val registration = registry.registerOrGet(callId, 30_000, AnswerDriver.APP)

        assertEquals(callId, registry.acknowledge(registration.attempt.requestId))
        assertNull(registry.fail(registration.attempt.requestId))
        assertEquals(AnswerOutcome.ACKNOWLEDGED, registration.attempt.outcome.await())

        val duplicate = registry.registerOrGet(callId, 30_000, AnswerDriver.APP)
        assertFalse(duplicate.created)
        assertEquals(registration.attempt.requestId, duplicate.attempt.requestId)

        assertTrue(registry.completeSuccessfully(callId))
        assertTrue(registration.attempt.completion.await())
    }

    @Test
    fun timeoutCannotBeCompletedAsSuccess() = runTest {
        val registry = PendingAnswerRegistry(this)
        val callId = UUID.randomUUID()
        val registration = registry.registerOrGet(callId, 1_000, AnswerDriver.APP)

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(AnswerOutcome.TIMED_OUT, registration.attempt.outcome.await())
        assertFalse(registry.completeSuccessfully(callId))
        assertTrue(registry.abandonFor(callId))
        assertFalse(registration.attempt.completion.await())
    }

    @Test
    fun telecomPromotesBeforeEitherDriverClaims() = runTest {
        val registry = PendingAnswerRegistry(this)
        val callId = UUID.randomUUID()
        val app = registry.registerOrGet(callId, 30_000, AnswerDriver.APP)
        val telecom = registry.registerOrGet(callId, 30_000, AnswerDriver.TELECOM)

        assertFalse(telecom.created)
        assertEquals(app.attempt.requestId, telecom.attempt.requestId)
        assertFalse(registry.claimDriver(callId, AnswerDriver.APP))
        assertTrue(registry.claimDriver(callId, AnswerDriver.TELECOM))
        assertFalse(registry.claimDriver(callId, AnswerDriver.TELECOM))

        registry.abandonFor(callId)
    }

    @Test
    fun boundedWaitSuspendsForAckAndExpiresBeforeTelecomDeadline() = runTest {
        val registry = PendingAnswerRegistry(this)
        val acknowledgedCall = UUID.randomUUID()
        val acknowledged = registry.registerOrGet(
            acknowledgedCall,
            30_000,
            AnswerDriver.TELECOM,
        )
        val waitingForAck = async {
            awaitAcknowledgedAnswer(acknowledged.attempt, 4_500) { true }
        }

        runCurrent()
        assertFalse(waitingForAck.isCompleted)
        registry.acknowledge(acknowledged.attempt.requestId)
        runCurrent()
        assertTrue(waitingForAck.await())
        registry.completeSuccessfully(acknowledgedCall)

        val timedOutCall = UUID.randomUUID()
        val timedOut = registry.registerOrGet(timedOutCall, 30_000, AnswerDriver.TELECOM)
        val waitingForTimeout = async {
            awaitAcknowledgedAnswer(timedOut.attempt, 4_500) { true }
        }

        advanceTimeBy(4_500)
        runCurrent()
        assertFalse(waitingForTimeout.await())
        registry.abandonFor(timedOutCall)
    }
}
