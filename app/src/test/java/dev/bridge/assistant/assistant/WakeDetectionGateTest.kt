package dev.bridge.assistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class WakeDetectionGateTest {
    @Test fun repeatedStartAndStopEventsAreIdempotentAndCanRearm() {
        val gate = WakeDetectionGate()
        assertTrue(gate.begin())
        assertFalse(gate.begin())
        assertTrue(gate.isActive())
        assertTrue(gate.stop())
        assertFalse(gate.stop())
        assertFalse(gate.isActive())
        assertTrue(gate.begin())
    }

    @Test fun concurrentStartsCreateOnlyOneActiveWorker() {
        val gate = WakeDetectionGate()
        val workers = 12
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val winners = AtomicInteger()
        val threads = (0 until workers).map {
            Thread {
                ready.countDown()
                start.await()
                if (gate.begin()) winners.incrementAndGet()
            }.apply { start() }
        }
        ready.await()
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, winners.get())
        assertTrue(gate.isActive())
    }
}
