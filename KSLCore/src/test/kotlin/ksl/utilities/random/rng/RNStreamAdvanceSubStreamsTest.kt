package ksl.utilities.random.rng

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Phase A: `RNStream.advanceSubStreams(n)` must be bit-identical to `n` calls of
 * `advanceToNextSubStream()`, while running in O(log n) rather than O(n).
 *
 * Equivalence is checked by comparing the random draws produced after positioning — if two
 * streams are at the same sub-stream, their draws are exactly equal (0.0 tolerance).
 */
class RNStreamAdvanceSubStreamsTest {

    private fun freshStream(): RNStreamIfc = RNStreamProvider().nextRNStream()

    private fun draws(s: RNStreamIfc, k: Int = 12): DoubleArray = DoubleArray(k) { s.randU01() }

    @Test
    fun advanceBySubStreamsMatchesRepeatedAdvance() {
        for (n in longArrayOf(1, 2, 5, 30, 100, 1000, 100_000)) {
            val looped = freshStream()
            repeat(n.toInt()) { looped.advanceToNextSubStream() }
            val jumped = freshStream()
            jumped.advanceSubStreams(n)
            assertArrayEquals(draws(looped), draws(jumped), 0.0, "draws mismatch for n=$n")
        }
    }

    @Test
    fun advanceByZeroLeavesStreamUnchanged() {
        val baseline = freshStream()
        val advanced = freshStream()
        advanced.advanceSubStreams(0)
        assertArrayEquals(draws(baseline), draws(advanced), 0.0)
    }

    @Test
    fun composedAdvancesEqualSingleAdvance() {
        val split = freshStream()
        split.advanceSubStreams(17)
        split.advanceSubStreams(25)
        val combined = freshStream()
        combined.advanceSubStreams(42)
        assertArrayEquals(draws(split), draws(combined), 0.0)
    }

    @Test
    fun negativeAdvanceThrows() {
        assertFailsWith<IllegalArgumentException> { freshStream().advanceSubStreams(-1) }
    }
}
