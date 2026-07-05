package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  Unit tests for [CleanUpProcedure]: subset-selection screening, indifference-zone vs. degraded
 *  selection, and the reported confidence interval in both modes.
 */
class CleanUpProcedureTest {

    private val pd: ProblemDefinition = IscTestSupport.boxProblem(dim = 1, lb = 0.0, ub = 100.0)

    private fun sol(x: Double, fx: Double, variance: Double = 4.0, count: Double = 10.0): Solution =
        IscTestSupport.solutionWith(pd, doubleArrayOf(x), fx, count).let {
            // rebuild with the desired variance (solutionWith uses variance 1.0)
            Solution(it.inputMap, ksl.simopt.evaluator.EstimatedResponse(pd.objFnResponseName, fx, variance, count), emptyList(), 1)
        }

    @Test
    fun screenKeepsTheBestAndDropsAClearlyWorseCandidate() {
        val best = sol(1.0, fx = 1.0)
        val close = sol(2.0, fx = 1.5)
        val worse = sol(3.0, fx = 100.0)
        val survivors = CleanUpProcedure(pd, deltaC = 0.0).screen(listOf(best, close, worse))
        assertTrue(survivors.any { it.inputMap == best.inputMap }, "the sample-best must survive screening")
        assertFalse(survivors.any { it.inputMap == worse.inputMap }, "a clearly worse candidate must be screened out")
    }

    @Test
    fun screenReturnsSingletonUnchanged() {
        val only = sol(1.0, fx = 1.0)
        assertEquals(listOf(only), CleanUpProcedure(pd, deltaC = 0.0).screen(listOf(only)))
    }

    @Test
    fun degradedSelectTakesNoExtraSamplesAndReturnsSampleBest() {
        val a = sol(1.0, fx = 5.0)
        val b = sol(2.0, fx = 2.0) // best (smallest mean)
        val c = sol(3.0, fx = 8.0)
        var sampleMoreCalls = 0
        val cleanUp = CleanUpProcedure(pd, deltaC = 0.0)
        val best = cleanUp.select(listOf(a, b, c)) { input, n ->
            sampleMoreCalls++
            IscTestSupport.solutionWith(pd, input.inputValues, 0.0, n.toDouble())
        }
        assertEquals(b.inputMap, best.inputMap, "degraded selection returns the smallest-mean survivor")
        assertEquals(0, sampleMoreCalls, "degraded selection must not take additional samples")
    }

    @Test
    fun indifferenceZoneSelectTakesSecondStageSamplesAndPicksTheBest() {
        val a = sol(1.0, fx = 5.0, variance = 4.0)
        val b = sol(2.0, fx = 2.0, variance = 4.0) // best
        var sampleMoreCalls = 0
        val cleanUp = CleanUpProcedure(pd, deltaC = 1.0)
        val best = cleanUp.select(listOf(a, b)) { input, n ->
            sampleMoreCalls++
            // return the additional replications at the same (deterministic) mean for this point
            val mean = if (input == b.inputMap) 2.0 else 5.0
            IscTestSupport.solutionWith(pd, input.inputValues, mean, n.toDouble())
        }
        assertEquals(b.inputMap, best.inputMap, "the IZ procedure must select the truly best system")
        assertTrue(sampleMoreCalls > 0, "the Rinott two-stage procedure must request additional samples")
    }

    @Test
    fun cleanUpCapsRinottSecondStageSampling() {
        // High variance with a tight deltaC drives the Rinott size N ~ (h*S/deltaC)^2 into the
        // thousands; the per-system cap must bound the second-stage request (best-effort selection).
        val a = sol(1.0, fx = 5.0, variance = 400.0)
        val b = sol(2.0, fx = 2.0, variance = 400.0) // best

        fun maxRequestedFor(cap: Int): Int {
            var maxN = 0
            val cleanUp = CleanUpProcedure(pd, deltaC = 0.5, maxReplicationsPerSystem = cap)
            cleanUp.select(listOf(a, b)) { input, n ->
                if (n > maxN) maxN = n
                val mean = if (input == b.inputMap) 2.0 else 5.0
                IscTestSupport.solutionWith(pd, input.inputValues, mean, n.toDouble())
            }
            return maxN
        }

        val cap = 100
        val cappedMax = maxRequestedFor(cap)
        val uncappedMax = maxRequestedFor(CleanUpProcedure.DEFAULT_MAX_REPLICATIONS_PER_SYSTEM)
        // first-stage count is 10, so a capped second stage adds at most cap - 10 replications
        assertTrue(cappedMax in 1..(cap - 10)) {
            "the cap must bound the second-stage request to at most ${cap - 10}, got $cappedMax"
        }
        assertTrue(uncappedMax > cappedMax) {
            "the uncapped run should request far more ($uncappedMax) than the capped run ($cappedMax)"
        }
    }

    @Test
    fun rejectsNonPositiveMaxReplicationsPerSystem() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            CleanUpProcedure(pd, deltaC = 1.0, maxReplicationsPerSystem = 0)
        }
    }

    @Test
    fun estimateWithIndifferenceZoneIsTheMeanPlusMinusDeltaC() {
        val best = sol(2.0, fx = 7.0)
        val ci = CleanUpProcedure(pd, deltaC = 1.5).estimate(best)
        assertEquals(7.0 - 1.5, ci.lowerLimit, 1e-12)
        assertEquals(7.0 + 1.5, ci.upperLimit, 1e-12)
    }

    @Test
    fun degradedEstimateIsAStudentTIntervalAroundTheMean() {
        val best = sol(2.0, fx = 7.0, variance = 4.0, count = 10.0)
        val ci = CleanUpProcedure(pd, deltaC = 0.0).estimate(best)
        assertTrue(ci.lowerLimit < 7.0 && ci.upperLimit > 7.0, "the t-interval must straddle the mean")
        assertTrue(ci.width > 0.0, "the degraded interval must have positive width")
    }
}
