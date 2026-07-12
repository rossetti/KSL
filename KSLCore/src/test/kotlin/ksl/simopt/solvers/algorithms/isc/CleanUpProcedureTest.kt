package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.statistic.Rinott
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

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

    @Test
    fun screeningUsesTheSplitConfidence() {
        // A1: the subset-selection screen must run at confidence 1 - alpha_C/2 (half of alpha_C; the
        // other half funds Rinott selection). With alpha_C = 0.05 the per-comparison t is at 0.975
        // (t_9 ~ 2.262, band ~ 1.012), not 0.95 (t_9 ~ 1.833, band ~ 0.820). SE = sqrt(1/10+1/10).
        // A candidate 0.9 above the best is inside the split band but outside the old (full-alpha) one.
        val cleanUp = CleanUpProcedure(pd, deltaC = 1.0, oneMinusAlphaC = 0.95)
        val best = sol(0.0, fx = 0.0, variance = 1.0, count = 10.0)
        val marginal = sol(1.0, fx = 0.9, variance = 1.0, count = 10.0)
        val retained = cleanUp.screen(listOf(best, marginal))
        assertTrue(retained.any { it.inputMap == marginal.inputMap },
            "the split-confidence (1 - alpha_C/2) screen must retain a candidate inside its wider band")
        assertEquals(2, retained.size, "both candidates survive the split-confidence screen")
    }

    @Test
    fun rinottSecondStageUsesTheSplitConfidence() {
        // A1: the Rinott second stage must use h at confidence 1 - alpha_C/2. Pin the requested
        // second-stage size N_i = max(n0, ceil((h*S/deltaC)^2)) against a hand-computed h at 0.975.
        val deltaC = 1.0
        val oneMinusAlpha = 0.95
        val n0 = 10.0
        val v = 4.0
        val cleanUp = CleanUpProcedure(pd, deltaC = deltaC, oneMinusAlphaC = oneMinusAlpha)
        val survivors = listOf(
            sol(0.0, fx = 5.0, variance = v, count = n0),
            sol(1.0, fx = 5.2, variance = v, count = n0)
        )
        val requested = HashMap<InputMap, Int>()
        cleanUp.select(survivors) { input, nAdd ->
            requested[input] = nAdd
            IscTestSupport.solutionWith(pd, input.inputValues, 5.0, nAdd.toDouble().coerceAtLeast(2.0))
        }
        val h = Rinott().rinottConstant(2, (1.0 + oneMinusAlpha) / 2.0, n0.toInt() - 1)
        val expectedN = maxOf(n0, ceil((h * sqrt(v) / deltaC).pow(2.0)))
        assertEquals((expectedN - n0).toInt(), requested[survivors[0].inputMap],
            "the Rinott second-stage size must use h at the split confidence 1 - alpha_C/2")
    }

    // ── B1: clean-up on response-feasible solutions ───────────────────────────

    /** A problem with one response constraint E[g] <= 5. */
    private fun constrainedProblem(): ProblemDefinition {
        val p = ProblemDefinition(
            problemName = "B1",
            modelIdentifier = "M",
            objFnResponseName = "y",
            inputNames = listOf("x"),
            responseNames = listOf("g")
        )
        p.inputVariable("x", lowerBound = 0.0, upperBound = 100.0, granularity = 1.0)
        p.responseConstraint("g", rhsValue = 5.0, inequalityType = InequalityType.LESS_THAN)
        return p
    }

    /** A solution with objective [obj] and a response estimate [g] (low variance so feasibility is clear). */
    private fun csol(p: ProblemDefinition, x: Double, obj: Double, g: Double): Solution =
        Solution(
            p.toInputMap(doubleArrayOf(x)),
            EstimatedResponse("y", obj, 1.0, 10.0),
            listOf(EstimatedResponse("g", g, 0.01, 10.0)),
            1
        )

    private fun neverCalledSampler(p: ProblemDefinition): (InputMap, Int) -> Solution =
        { input, n -> Solution(input, EstimatedResponse("y", 0.0, 1.0, n.toDouble().coerceAtLeast(2.0)), emptyList(), 1) }

    @Test
    fun cleanUpSelectsFromTheResponseFeasibleSubset() {
        // An infeasible optimum with a LOWER objective must not be returned; ranking happens only
        // within the response-feasible subset.
        val p = constrainedProblem()
        val cleanUp = CleanUpProcedure(p, deltaC = 0.0)
        val feasibleHigher = csol(p, x = 1.0, obj = 10.0, g = 2.0)   // feasible (g=2 <= 5)
        val infeasibleLower = csol(p, x = 2.0, obj = 1.0, g = 9.0)   // infeasible (g=9 > 5), lower objective
        val result = cleanUp.cleanUp(listOf(feasibleHigher, infeasibleLower), neverCalledSampler(p))
        assertTrue(result.usedFeasibleSubset, "a feasible subset exists and must be used")
        assertEquals(feasibleHigher.inputMap, result.best.inputMap,
            "clean-up must select the response-feasible optimum, not the lower-objective infeasible one")
    }

    @Test
    fun cleanUpFallsBackToLeastInfeasibleWhenNoneAreFeasible() {
        // With no response-feasible optimum, return the least-infeasible (minimum total violation)
        // with a plain CI (not the IZ +/- deltaC), even though another candidate has a lower objective.
        val p = constrainedProblem()
        val cleanUp = CleanUpProcedure(p, deltaC = 1.0)
        val lessInfeasible = csol(p, x = 1.0, obj = 10.0, g = 6.0)   // violation 1, higher objective
        val moreInfeasible = csol(p, x = 2.0, obj = 1.0, g = 9.0)    // violation 4, lower objective
        val result = cleanUp.cleanUp(listOf(lessInfeasible, moreInfeasible), neverCalledSampler(p))
        assertFalse(result.usedFeasibleSubset, "no candidate is response-feasible")
        assertEquals(lessInfeasible.inputMap, result.best.inputMap,
            "the fallback returns the least-infeasible (minimum total violation) solution")
        // Plain t-interval on the objective mean (10 +/- t*s/sqrt(n) with t at 0.975), not 10 +/- deltaC(=1).
        assertTrue(result.confidenceInterval.width > 0.0, "the fallback CI has positive width")
        assertTrue(result.confidenceInterval.lowerLimit > 10.0 - 1.0,
            "the fallback CI must be a plain interval, narrower than the IZ +/- deltaC")
    }
}
