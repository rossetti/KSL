package ksl.utilities.random.rvariable

import ksl.utilities.math.FunctionIfc
import ksl.utilities.random.mcmc.MetropolisHastings1D
import ksl.utilities.random.mcmc.ProposalFunction1DIfc
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.robj.BernoulliPicker
import ksl.utilities.random.robj.DUniformList
import ksl.utilities.random.robj.RMap
import ksl.utilities.statistic.CaseBootEstimatorIfc
import ksl.utilities.statistic.CaseBootstrapSampler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The stream-number correction was applied to nine classes and covered by tests on three. The
 * fix is the same two lines in each -- remember the number the object was built with, and ask
 * the provider only when the request was "the next stream" -- so the risk in the other six is
 * low. It is not zero: the correction exists because a provider cannot name an antithetic stream
 * it does not hold and returns -1 for it, and a -1 carried into a rebinding silently becomes a
 * request for antithetic stream 1. That failure is invisible. Every class that reports a stream
 * number is a place it can happen, so every class that was changed is checked here.
 *
 * `RVariable`, `DEmpiricalList` and `DPopulation` are covered by
 * `StreamNumberRoundTripTest`; this covers the rest. `RList` is exercised through
 * `DUniformList` and `MVRVariable` through `MVNormalRV`, those being the concrete classes that
 * carry the inherited implementation -- `DEmpiricalList`, despite the name, is not an `RList`.
 */
class StreamNumberRoundTripCoverageTest {

    /** A trivial estimator, enough to construct a bootstrap sampler. */
    private class MeanOfCases(private val data: DoubleArray) : CaseBootEstimatorIfc {
        override val names: List<String> = listOf("mean")
        override val originalEstimates: DoubleArray = doubleArrayOf(data.average())
        override val caseIdentifiers: List<Int> = data.indices.toList()
        override fun estimate(caseIndices: IntArray): DoubleArray =
            doubleArrayOf(caseIndices.map { data[it] }.average())
    }

    /**
     * The three cases that matter for every one of these classes: an ordinary stream reports
     * itself, an antithetic stream reports itself rather than -1, and zero resolves against the
     * provider because "the next stream" is not known until one has been served.
     */
    private fun checkRoundTrip(
        description: String,
        build: (streamNum: Int, provider: RNStreamProvider) -> Int
    ) {
        val provider = RNStreamProvider()
        assertEquals(2, build(2, provider)) { "$description did not report an ordinary stream number" }
        assertEquals(-4, build(-4, provider)) {
            "$description did not report the antithetic stream it was built with; a -1 here is " +
                "the old failure, and rebinding turns it into antithetic stream 1"
        }
        // zero is a request, not a number: whatever the provider serves next is the answer
        val fresh = RNStreamProvider()
        fresh.rnStream(7)
        assertEquals(8, build(0, fresh)) { "$description did not resolve a next-stream request" }
    }

    @Test
    @DisplayName("RList reports the stream number it was built with")
    fun rListRoundTrips() {
        checkRoundTrip("DUniformList") { streamNum, provider ->
            DUniformList(mutableListOf("a", "b", "c"), streamNum, provider).streamNumber
        }
    }

    @Test
    @DisplayName("RMap reports the stream number it was built with")
    fun rMapRoundTrips() {
        checkRoundTrip("RMap") { streamNum, provider ->
            RMap(mapOf("a" to 1, "b" to 2), streamNum, provider).streamNumber
        }
    }

    @Test
    @DisplayName("BernoulliPicker reports the stream number it was built with")
    fun bernoulliPickerRoundTrips() {
        checkRoundTrip("BernoulliPicker") { streamNum, provider ->
            BernoulliPicker(0.5, "yes", "no", streamNum, provider).streamNumber
        }
    }

    @Test
    @DisplayName("MVRVariable reports the stream number it was built with")
    fun mvRVariableRoundTrips() {
        val means = doubleArrayOf(0.0, 0.0)
        val covariances = arrayOf(doubleArrayOf(1.0, 0.5), doubleArrayOf(0.5, 1.0))
        checkRoundTrip("MVNormalRV") { streamNum, provider ->
            MVNormalRV(means, covariances, streamNum, provider).streamNumber
        }
    }

    @Test
    @DisplayName("MetropolisHastings1D reports the stream number it was built with")
    fun metropolisHastingsRoundTrips() {
        val target = FunctionIfc { x -> if (x in 0.0..1.0) 1.0 else 0.0 }
        val proposal = object : ProposalFunction1DIfc {
            override fun proposalRatio(currentX: Double, proposedX: Double): Double = 1.0
            override fun generateProposedGivenCurrent(currentX: Double): Double = currentX
        }
        checkRoundTrip("MetropolisHastings1D") { streamNum, provider ->
            MetropolisHastings1D(0.5, target, proposal, streamNum, provider).streamNumber
        }
    }

    @Test
    @DisplayName("CaseBootstrapSampler reports the stream number it was built with")
    fun caseBootstrapSamplerRoundTrips() {
        val estimator = MeanOfCases(doubleArrayOf(1.0, 2.0, 3.0, 4.0))
        checkRoundTrip("CaseBootstrapSampler") { streamNum, provider ->
            CaseBootstrapSampler(estimator, streamNum, provider).streamNumber
        }
    }
}
