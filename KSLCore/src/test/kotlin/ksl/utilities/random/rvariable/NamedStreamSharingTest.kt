package ksl.utilities.random.rvariable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A stream number selects a stream; it does not rewind one.
 *
 * This is intended behaviour, not a defect, and it is pinned here because it is silent and easy to
 * get wrong. Naming a stream reads as a request for reproducibility and delivers that only to the
 * first consumer of the stream in the process: a function building a fixed data set from a named
 * stream returns a different data set the second time it is called, and the result then depends on
 * the order the suite happens to run in.
 *
 * These tests exist so the documented contract has something holding it in place, and so that a
 * future change making construction rewind is a deliberate decision rather than an accident.
 */
class NamedStreamSharingTest {

    private fun firstFive(rv: RVariableIfc): List<Double> = List(5) { rv.value }

    @Test
    @DisplayName("Two variables on the same stream number share it, and the second continues")
    fun theSameStreamNumberIsSharedNotRewound() {
        val a = ExponentialRV(10.0, streamNum = 16)
        val b = ExponentialRV(10.0, streamNum = 16)

        val fromA = firstFive(a)
        val fromB = firstFive(b)
        assertNotEquals(fromA, fromB) {
            "constructing on a named stream appears to rewind it now; that is a behaviour change, " +
                "not a bug fix, and the documentation on RVariable says the opposite"
        }
    }

    @Test
    @DisplayName("resetStartStream is what rewinds")
    fun resetStartStreamRewinds() {
        val a = ExponentialRV(10.0, streamNum = 16)
        val b = ExponentialRV(10.0, streamNum = 16)

        val fromA = firstFive(a)
        firstFive(b)
        b.resetStartStream()
        assertEquals(fromA, firstFive(b)) {
            "after resetStartStream the series must be the one the stream starts with"
        }
    }

    /** The remedy stated as the recipe: reset after construction to mean "this exact series". */
    @Test
    @DisplayName("Resetting after construction makes a named stream reproducible")
    fun resettingAfterConstructionIsReproducible() {
        fun fixedDataSet(): List<Double> {
            val rv = ExponentialRV(10.0, streamNum = 17)
            rv.resetStartStream()
            return List(5) { rv.value }
        }
        assertEquals(fixedDataSet(), fixedDataSet()) {
            "the documented workaround no longer produces a repeatable data set"
        }
    }

    /** Different stream numbers are independent, which is the property the sharing does not break. */
    @Test
    @DisplayName("Different stream numbers give different series")
    fun differentStreamNumbersAreIndependent() {
        val a = ExponentialRV(10.0, streamNum = 18).also { it.resetStartStream() }
        val b = ExponentialRV(10.0, streamNum = 19).also { it.resetStartStream() }
        assertNotEquals(firstFive(a), firstFive(b))
    }
}
