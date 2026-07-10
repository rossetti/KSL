package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.problem.ProblemDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 *  End-to-end tests for [NichingGeneticAlgorithmSolver] on deterministic integer objectives: niche
 *  discovery, feasibility, reproducibility, and the firing of the global→local transition rules.
 */
class NichingGeneticAlgorithmSolverTest {

    // Domain [0,30] (31 feasible integer points) so the population size stays below the number of
    // distinct feasible points (sampling unique feasible points otherwise cannot fill the population).
    private fun bimodalProblem(): ProblemDefinition =
        IscTestSupport.boxProblem(dim = 1, lb = 0.0, ub = 30.0, granularity = 1.0)

    /** Minima at x = 8 and x = 22 (both value 0). */
    private fun bimodal(x: DoubleArray): Double =
        minOf((x[0] - 8.0) * (x[0] - 8.0), (x[0] - 22.0) * (x[0] - 22.0))

    private fun makeSolver(
        objective: (DoubleArray) -> Double,
        streamNum: Int = 1,
        populationSize: Int = 16,
        maxIterations: Int = 30,
        transitionRules: List<NgaTransitionRuleIfc> = listOf(SingleNicheRule(), ImprovementRule())
    ): NichingGeneticAlgorithmSolver {
        val pd = bimodalProblem()
        val evaluator = IscTestSupport.FunctionEvaluator(pd, objective)
        return NichingGeneticAlgorithmSolver(
            problemDefinition = pd,
            evaluator = evaluator,
            streamNum = streamNum,
            populationSize = populationSize,
            maxIterations = maxIterations,
            transitionRules = transitionRules,
            replicationsPerEvaluation = 3
        )
    }

    @Test
    fun runsAndReturnsFeasibleNichesOnABimodalObjective() {
        val solver = makeSolver(::bimodal)
        solver.runAllIterations()
        assertTrue(solver.niches.isNotEmpty(), "the global phase must return at least one niche")
        val best = solver.bestSolution
        assertTrue(best.isInputFeasible(), "the best solution must be feasible")
        assertTrue(best.inputMap.inputValues[0] in 0.0..10.0, "the best solution must be within bounds")
    }

    @Test
    fun drivesTheIncumbentTowardAMinimum() {
        val solver = makeSolver(::bimodal, maxIterations = 40)
        solver.runAllIterations()
        assertTrue(solver.bestSolution.estimatedObjFncValue < 4.0,
            "the NGA should drive the incumbent near a minimum (was ${solver.bestSolution.estimatedObjFncValue})")
    }

    @Test
    fun runsAreReproducibleForAFixedStreamNumber() {
        val a = makeSolver(::bimodal, streamNum = 7)
        val b = makeSolver(::bimodal, streamNum = 7)
        a.runAllIterations()
        b.runAllIterations()
        assertEquals(a.bestSolution.inputMap, b.bestSolution.inputMap,
            "the same stream number must reproduce the same best inputs")
    }

    @Test
    fun singleNicheRuleTerminatesAUnimodalSearch() {
        val solver = makeSolver(
            objective = { x -> (x[0] - 15.0) * (x[0] - 15.0) },
            maxIterations = 50,
            transitionRules = listOf(SingleNicheRule())
        )
        solver.runAllIterations()
        assertTrue(solver.currentNiches.count <= 1,
            "the single-niche rule's stopping condition must hold at termination")
    }

    @Test
    fun budgetRuleStopsTheGlobalPhase() {
        val budget = 200
        val solver = makeSolver(
            ::bimodal,
            maxIterations = 100,
            transitionRules = listOf(BudgetRule(replicationBudget = budget))
        )
        solver.runAllIterations()
        assertTrue(solver.numReplicationsRequested >= budget,
            "the budget rule must stop only after the replication budget is reached")
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun sampleInputFeasiblePointsTerminatesWhenRequestExceedsFeasibleSpace() {
        // When the request exceeds the number of distinct feasible points, sampling must not loop
        // forever. With the grid (31 points) no larger than the request (100), the sampler enumerates
        // the feasible set exactly (deterministic, no wasted draws) and returns all 31 points — exactly
        // the configuration (ISC's default global phase, population 50, on a small integer domain) that
        // previously hung. SEPARATE_THREAD is required for @Timeout to actually preempt a hang (the
        // default SAME_THREAD mode only checks duration AFTER the test returns), so a regression that
        // reintroduced unbounded looping would fail at 15s rather than spin the whole suite.
        val solver = makeSolver(::bimodal)
        val points = solver.sampleInputFeasiblePoints(100)
        assertEquals(31, points.size,
            "must return all 31 distinct feasible points, not hang trying to reach 100")
    }

    @Test
    fun sampleInputFeasiblePointsEnumeratesDeterministicallyWhenTheGridFitsTheRequest() {
        // The grid (31 points) fits the request (100), so enumeration is used: independent of the
        // stream, it returns the identical full feasible set.
        val a = makeSolver(::bimodal, streamNum = 1).sampleInputFeasiblePoints(100)
        val b = makeSolver(::bimodal, streamNum = 999).sampleInputFeasiblePoints(100)
        assertEquals(31, a.size)
        assertEquals(a, b, "enumeration is deterministic and independent of the stream")
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun sampleInputFeasiblePointsBoundsRejectionWhenConstraintsShrinkALargeGrid() {
        // When the grid is larger than the request (so enumeration does not apply) but linear
        // constraints leave fewer feasible points than requested, rejection sampling must still be
        // bounded: collect the feasible points that exist and stop, not loop forever.
        val pd = IscTestSupport.boxProblem(dim = 2, lb = 0.0, ub = 10.0, granularity = 1.0) // 121-point grid
        pd.linearConstraint(mapOf("x1" to 1.0, "x2" to 1.0), 2.0, IscTestSupport.LE) // x1+x2 <= 2
        pd.maxFeasibleSamplingIterations = 2000
        val solver = NichingGeneticAlgorithmSolver(
            problemDefinition = pd,
            evaluator = IscTestSupport.FunctionEvaluator(pd, IscTestSupport.sphere(doubleArrayOf(0.0, 0.0))),
            streamNum = 1,
            populationSize = 16,
            maxIterations = 1,
            transitionRules = listOf(SingleNicheRule()),
            replicationsPerEvaluation = 1
        )
        // grid 121 > request 10 -> rejection path; feasible points < 10 -> the bound must stop it
        val expected = pd.enumerateFeasibleInputPoints(1000)!!.size // exact feasible count
        val points = solver.sampleInputFeasiblePoints(10)
        assertEquals(expected, points.size, "rejection collects exactly the feasible grid points, then stops")
        assertTrue(points.all { pd.isInputFeasible(it.inputValues) }, "returned points are input feasible")
    }
}
