package io.github.mangi.eta.agent.tool

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ForegroundExclusiveGateTest {
    @Before
    fun reset() {
        ForegroundExclusiveGate.resetForTests()
    }

    @After
    fun cleanup() {
        ForegroundExclusiveGate.resetForTests()
    }

    @Test
    fun serializesForegroundAndBrowserToolsOnly() {
        assertTrue(ForegroundExclusiveGate.shouldSerialize("observe_screen"))
        assertTrue(ForegroundExclusiveGate.shouldSerialize("tap_element"))
        assertTrue(ForegroundExclusiveGate.shouldSerialize("browser_use"))
        assertFalse(ForegroundExclusiveGate.shouldSerialize("memory_get"))
        assertFalse(ForegroundExclusiveGate.shouldSerialize("terminal"))
        assertFalse(ForegroundExclusiveGate.shouldSerialize("web_search"))
    }

    @Test
    fun sameRunReacquireDoesNotBlock() {
        assertTrue(ForegroundExclusiveGate.acquire("run-a"))
        assertTrue(ForegroundExclusiveGate.acquire("run-a"))
        assertEquals("run-a", ForegroundExclusiveGate.ownerForTests())
        ForegroundExclusiveGate.release("run-a")
        assertNull(ForegroundExclusiveGate.ownerForTests())
    }

    @Test
    fun laterRunWaitsUntilOwnerRunReleases() {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val secondAcquired = AtomicBoolean(false)
        assertTrue(ForegroundExclusiveGate.acquire("run-a"))
        val waiter = Thread {
            started.countDown()
            val ok = ForegroundExclusiveGate.acquire("run-b")
            secondAcquired.set(ok)
            released.countDown()
        }
        waiter.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        Thread.sleep(80)
        assertFalse(secondAcquired.get())
        assertEquals("run-a", ForegroundExclusiveGate.ownerForTests())
        ForegroundExclusiveGate.release("run-a")
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertTrue(secondAcquired.get())
        assertEquals("run-b", ForegroundExclusiveGate.ownerForTests())
        ForegroundExclusiveGate.release("run-b")
        waiter.join(1_000)
    }

    @Test
    fun waitingRunsStartInQueueOrder() {
        val order = ArrayList<String>()
        val bWaiting = CountDownLatch(1)
        val cWaiting = CountDownLatch(1)
        val bOwned = CountDownLatch(1)
        val cOwned = CountDownLatch(1)
        assertTrue(ForegroundExclusiveGate.acquire("run-a"))
        val b = Thread {
            val probe = Thread {
                while (ForegroundExclusiveGate.waitersForTests() != listOf("run-b") &&
                    !Thread.currentThread().isInterrupted
                ) {
                    Thread.sleep(10)
                }
                bWaiting.countDown()
            }
            probe.start()
            assertTrue(ForegroundExclusiveGate.acquire("run-b"))
            probe.interrupt()
            synchronized(order) { order += "run-b" }
            bOwned.countDown()
        }
        val c = Thread {
            val probe = Thread {
                while (ForegroundExclusiveGate.waitersForTests() != listOf("run-b", "run-c") &&
                    !Thread.currentThread().isInterrupted
                ) {
                    Thread.sleep(10)
                }
                cWaiting.countDown()
            }
            probe.start()
            assertTrue(ForegroundExclusiveGate.acquire("run-c"))
            probe.interrupt()
            synchronized(order) { order += "run-c" }
            cOwned.countDown()
        }
        b.start()
        assertTrue(bWaiting.await(2, TimeUnit.SECONDS))
        c.start()
        assertTrue(cWaiting.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("run-b", "run-c"), ForegroundExclusiveGate.waitersForTests())
        ForegroundExclusiveGate.release("run-a")
        assertTrue(bOwned.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("run-b"), synchronized(order) { order.toList() })
        ForegroundExclusiveGate.release("run-b")
        assertTrue(cOwned.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("run-b", "run-c"), synchronized(order) { order.toList() })
        ForegroundExclusiveGate.release("run-c")
        b.join(1_000)
        c.join(1_000)
    }

    @Test
    fun closedRunLeavesQueueWithoutTakingOwnership() {
        val closed = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val acquired = AtomicBoolean(true)
        assertTrue(ForegroundExclusiveGate.acquire("run-a"))
        val waiter = Thread {
            started.countDown()
            acquired.set(ForegroundExclusiveGate.acquire("run-b") { closed.get() })
            done.countDown()
        }
        waiter.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        Thread.sleep(50)
        closed.set(true)
        ForegroundExclusiveGate.release("run-b")
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertFalse(acquired.get())
        assertEquals("run-a", ForegroundExclusiveGate.ownerForTests())
        ForegroundExclusiveGate.release("run-a")
        waiter.join(1_000)
    }

    @Test
    fun ownerSurvivesOtherToolsUntilRelease() {
        val steps = AtomicInteger(0)
        assertTrue(ForegroundExclusiveGate.acquire("run-a"))
        steps.incrementAndGet()
        assertTrue(ForegroundExclusiveGate.acquire("run-a"))
        steps.incrementAndGet()
        assertEquals(2, steps.get())
        assertEquals("run-a", ForegroundExclusiveGate.ownerForTests())
        ForegroundExclusiveGate.release("run-a")
    }
}
