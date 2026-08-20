package ksl.utilities.random.rng

import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A provider is shared by every random variable that does not name its own, so it is reachable
 * from more than one thread whenever models are built concurrently -- which is the ordinary case
 * for parallel simulation and for benchmark studies.
 *
 * The failure these guard against is quiet rather than loud. A stream's NUMBER is its position in
 * the provider's list of provided streams, and asking for a stream beyond the end of that list
 * grows it. Two threads growing it at the same time each land at a different index, so a random
 * variable that asked for stream 1 can report itself as stream 6 -- and a model that rebinds a
 * supplied variable onto its own provider by that number then draws from the wrong stream. Every
 * model stays internally consistent; they simply stop agreeing with each other, and which model
 * gets which stream depends on thread timing.
 */
@Timeout(60)
class RNStreamProviderConcurrencyTest {

    private companion object {
        const val THREADS = 8
        const val ROUNDS = 40
    }

    /** Runs [work] on [THREADS] threads at once and returns the results in submission order. */
    private fun <T> inParallel(count: Int = THREADS, work: () -> T): List<T> {
        val pool = Executors.newFixedThreadPool(count)
        try {
            val tasks = List(count) { Callable { work() } }
            return pool.invokeAll(tasks).map { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }
    }

    @Test
    @DisplayName("Concurrent requests for the same stream all get that stream, and agree on its number")
    fun concurrentRequestsForOneStreamAgree() {
        repeat(ROUNDS) {
            val provider = RNStreamProvider()
            val streams = inParallel { provider.rnStream(1) }
            val first = streams.first()
            for (stream in streams) {
                assertSame(first, stream) { "Concurrent requests for stream 1 returned different streams" }
                assertEquals(1, provider.streamNumber(stream)) { "Stream 1 reported a different number" }
            }
            assertEquals(1, provider.lastRNStreamNumber()) {
                "Only one stream should have been provided"
            }
        }
    }

    @Test
    @DisplayName("A random variable built concurrently reports the stream number it asked for")
    fun concurrentlyBuiltRandomVariablesKeepTheirStreamNumber() {
        // This is the shape that actually occurs: several threads each build a model, and each
        // model's random variables are constructed against the shared default provider before
        // being rebound onto the model's own by stream number.
        repeat(ROUNDS) {
            val provider = RNStreamProvider()
            val numbers = inParallel { ExponentialRV(1.0, streamNum = 3, streamProvider = provider).streamNumber }
            assertTrue(numbers.all { it == 3 }) {
                "Variables that all asked for stream 3 reported $numbers"
            }
        }
    }

    @Test
    @DisplayName("Concurrent requests for distinct streams each get the stream they asked for")
    fun concurrentRequestsForDistinctStreamsAreCorrect() {
        repeat(ROUNDS) {
            val provider = RNStreamProvider()
            val pool = Executors.newFixedThreadPool(THREADS)
            try {
                val tasks = (1..THREADS).map { k -> Callable { k to provider.rnStream(k) } }
                for ((asked, stream) in pool.invokeAll(tasks).map { it.get() }) {
                    assertEquals(asked, provider.streamNumber(stream)) {
                        "A request for stream $asked returned a stream numbered ${provider.streamNumber(stream)}"
                    }
                }
            } finally {
                pool.shutdown()
                pool.awaitTermination(30, TimeUnit.SECONDS)
            }
            assertEquals(THREADS, provider.lastRNStreamNumber())
        }
    }

    @Test
    @DisplayName("The streams snapshot can be iterated while another thread asks for a new stream")
    fun streamsSnapshotIsSafeToIterateDuringGrowth() {
        val provider = RNStreamProvider()
        provider.rnStream(4)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val grower = Callable { repeat(200) { provider.nextRNStream() }; 0 }
            val reader = Callable {
                var seen = 0
                repeat(200) {
                    val itr = provider.streams
                    while (itr.hasNext()) { itr.next(); seen++ }
                }
                seen
            }
            val results = pool.invokeAll(listOf(grower, reader)).map { it.get() }
            assertTrue(results[1] > 0) { "The reader saw no streams" }
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }
    }
}
