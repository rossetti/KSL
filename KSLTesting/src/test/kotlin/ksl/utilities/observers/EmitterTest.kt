package ksl.utilities.observers

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class EmitterTest {

    @Test
    fun `concurrent attach does not lose subscriptions`() {
        val emitter = Emitter<Int>()
        val threadCount = 50
        val fireCount = AtomicInteger(0)
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            executor.submit {
                emitter.attach { fireCount.incrementAndGet() }
                latch.countDown()
            }
        }
        latch.await()
        executor.shutdown()

        emitter.emit(1)

        assertEquals(threadCount, fireCount.get(),
            "Every concurrently-attached subscriber must receive the emission")
    }

    @Test
    fun `detach during emit does not throw and remaining callbacks still fire`() {
        val emitter = Emitter<Int>()
        var secondFired = false
        var selfDetachConnection: Emitter.Connection? = null

        selfDetachConnection = emitter.attach {
            emitter.detach(selfDetachConnection!!)
        }
        emitter.attach { secondFired = true }

        assertDoesNotThrow { emitter.emit(1) }
        assertTrue(secondFired, "Second subscriber must still fire after first detaches itself")
    }

    @Test
    fun `isObserved reflects attach and detach`() {
        val emitter = Emitter<String>()
        assertFalse(emitter.isObserved)

        val connection = emitter.attach { }
        assertTrue(emitter.isObserved)

        emitter.detach(connection)
        assertFalse(emitter.isObserved)
    }

    @Test
    fun `emissionsOn false suppresses all callbacks`() {
        val emitter = Emitter<Int>()
        var fired = false
        emitter.attach { fired = true }

        emitter.emissionsOn = false
        emitter.emit(1)

        assertFalse(fired)
        assertFalse(emitter.isObserved, "isObserved must be false when emissionsOn is false")
    }
}
