package ksl.utilities.random.rvariable

import ksl.modeling.variable.RandomVariable
import ksl.simulation.Model
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.robj.DEmpiricalList
import ksl.utilities.random.robj.DPopulation
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A random variable is asked for a stream number when it is built, and is asked to report one
 * whenever it is copied onto another provider. Those two numbers have to agree.
 *
 * They matter because a model does not adopt a supplied random variable directly: it rebinds the
 * variable onto the model's own provider, carrying the STREAM NUMBER across rather than the
 * stream. If the number a variable reports is not the one it was built with, the model quietly
 * draws from a different stream than the one that was asked for -- no error, no warning, just
 * different numbers.
 *
 * The antithetic case is where that used to bite. A provider serves an antithetic stream as a
 * derived copy that it does not itself hold, so a variable built on stream -3 could not be found
 * in the provider's list and reported -1; rebinding then consumed that -1 as a request and landed
 * on antithetic stream 1. Anyone setting up antithetic variates got the wrong pairing silently.
 */
class StreamNumberRoundTripTest {

    private companion object {
        const val DRAWS = 5
    }

    private fun sequence(rv: RVariableIfc): List<Double> = List(DRAWS) { rv.value }

    /*
     * A provider serves a POSITIVE stream number as the stream itself, so two variables built on
     * the same number share one stream and drawing from either advances both. An antithetic
     * number is served as a fresh copy, which does not. Comparisons below therefore draw the
     * reference sequence first and rewind the underlying stream before drawing the actual one,
     * which is correct for either kind.
     */
    private fun rewind(provider: RNStreamProvider, streamNum: Int) {
        provider.rnStream(abs(streamNum)).resetStartStream()
    }

    @Test
    @DisplayName("A variable reports the stream number it was built with")
    fun streamNumberRoundTrips() {
        val provider = RNStreamProvider()
        assertEquals(1, ExponentialRV(1.0, streamNum = 1, streamProvider = provider).streamNumber)
        assertEquals(3, ExponentialRV(1.0, streamNum = 3, streamProvider = provider).streamNumber)
        assertEquals(-3, ExponentialRV(1.0, streamNum = -3, streamProvider = provider).streamNumber) {
            "An antithetic variable must report the antithetic stream it was built with"
        }
    }

    @Test
    @DisplayName("Stream number zero means the next stream, and resolves to the one provided")
    fun streamNumberZeroResolvesToTheProvidedStream() {
        val provider = RNStreamProvider()
        provider.rnStream(4)
        // zero is a request for the next stream, so the number is not known until it is served
        assertEquals(5, ExponentialRV(1.0, streamNum = 0, streamProvider = provider).streamNumber)
        assertEquals(6, ExponentialRV(1.0, streamNum = 0, streamProvider = provider).streamNumber)
    }

    @Test
    @DisplayName("A model rebinds an antithetic source onto the antithetic of the same stream")
    fun antitheticSourceSurvivesRebindingIntoAModel() {
        val outside = RNStreamProvider()
        val model = Model("rebindProbe")

        val source = ExponentialRV(1.0, streamNum = -3, streamProvider = outside)
        val bound = RandomVariable(model, source)

        // the two candidates on the MODEL's own provider; an antithetic stream is served as a
        // copy, so building these does not disturb the streams they are taken from
        val antithetic3 = ExponentialRV(1.0, streamNum = -3, streamProvider = model.streamProvider)
        val antithetic1 = ExponentialRV(1.0, streamNum = -1, streamProvider = model.streamProvider)
        val expected3 = sequence(antithetic3)
        val expected1 = sequence(antithetic1)
        rewind(model.streamProvider, 3)
        rewind(model.streamProvider, 1)
        val boundDraws = sequence(bound.randomSource)

        assertEquals(expected3, boundDraws) {
            "A source built on antithetic stream 3 must be rebound onto antithetic stream 3"
        }
        assertNotEquals(expected1, boundDraws) {
            "The rebound source fell back to antithetic stream 1"
        }
        assertTrue(bound.randomSource.antithetic) { "The rebound source lost its antithetic setting" }
    }

    @Test
    @DisplayName("A model rebinds an ordinary source onto the same stream number")
    fun ordinarySourceSurvivesRebindingIntoAModel() {
        val outside = RNStreamProvider()
        val model = Model("rebindProbeOrdinary")

        val source = ExponentialRV(1.0, streamNum = 3, streamProvider = outside)
        val bound = RandomVariable(model, source)

        assertEquals(3, bound.streamNumber)
        val expected = sequence(ExponentialRV(1.0, streamNum = 3, streamProvider = model.streamProvider))
        rewind(model.streamProvider, 3)
        assertEquals(expected, sequence(bound.randomSource)) {
            "A source built on stream 3 must be rebound onto stream 3"
        }
    }

    @Test
    @DisplayName("Copying a variable onto another provider preserves its stream, antithetic or not")
    fun instanceOnAnotherProviderPreservesTheStream() {
        val first = RNStreamProvider()
        val second = RNStreamProvider()

        for (streamNum in listOf(2, -2)) {
            rewind(second, streamNum)
            val original = ExponentialRV(1.0, streamNum = streamNum, streamProvider = first)
            val copy = original.instance(original.streamNumber, second)
            assertEquals(streamNum, copy.streamNumber) {
                "A copy of a variable on stream $streamNum reported ${copy.streamNumber}"
            }
            val expected = sequence(ExponentialRV(1.0, streamNum = streamNum, streamProvider = second))
            rewind(second, streamNum)
            assertEquals(expected, sequence(copy)) {
                "The copy does not draw from stream $streamNum of the second provider"
            }
        }
    }

    @Test
    @DisplayName("Random-object selectors report the stream number they were built with")
    fun randomObjectSelectorsRoundTripTheirStreamNumber() {
        // The robj family rides the same rebinding path -- RandomElement copies a source onto the
        // model's provider by stream number exactly as RandomVariable does -- so it needs the
        // same guarantee.
        val provider = RNStreamProvider()
        val elements = listOf("a", "b", "c")
        val cdf = doubleArrayOf(1.0 / 3.0, 2.0 / 3.0, 1.0)

        assertEquals(2, DEmpiricalList(elements, cdf, streamNum = 2, streamProvider = provider).streamNumber)
        assertEquals(-2, DEmpiricalList(elements, cdf, streamNum = -2, streamProvider = provider).streamNumber) {
            "An antithetic selector must report the antithetic stream it was built with"
        }

        val data = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(4, DPopulation(data, streamNum = 4, streamProvider = provider).streamNumber)
        assertEquals(-4, DPopulation(data, streamNum = -4, streamProvider = provider).streamNumber)
    }
}
