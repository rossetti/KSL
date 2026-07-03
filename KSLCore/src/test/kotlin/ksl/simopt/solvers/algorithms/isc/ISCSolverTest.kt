package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.random.rng.RNStreamProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  End-to-end tests for the three-phase [ISCSolver] on deterministic in-memory objectives: the
 *  unimodal COMPASS-only shortcut, the full global→local→clean-up pipeline, the reported confidence
 *  interval in both indifference-zone modes, and reproducibility.
 */
class ISCSolverTest {

    // Domain [0,30] keeps the population below the number of distinct feasible points.
    private fun problem(): ProblemDefinition =
        IscTestSupport.boxProblem(dim = 1, lb = 0.0, ub = 30.0, granularity = 1.0)

    /** Minima at x = 8 and x = 22 (both value 0). */
    private fun bimodal(x: DoubleArray): Double =
        minOf((x[0] - 8.0) * (x[0] - 8.0), (x[0] - 22.0) * (x[0] - 22.0))

    private fun unimodal(x: DoubleArray): Double = (x[0] - 15.0) * (x[0] - 15.0)

    @Test
    fun unimodalShortcutReturnsAFeasibleBest() {
        val pd = problem()
        val evaluator = IscTestSupport.FunctionEvaluator(pd, ::unimodal)
        val isc = ISCSolver(
            problemDefinition = pd,
            evaluator = evaluator,
            streamNum = 1,
            replicationsPerEvaluation = 3,
            deltaC = 0.0,
            skipGlobalPhase = true
        )
        isc.runAllIterations()
        val best = isc.bestSolution
        assertTrue(best.isInputFeasible(), "the best solution must be feasible")
        assertTrue(best.estimatedObjFncValue < 4.0, "COMPASS-only should approach the single minimum (x=15)")
        assertEquals(1, isc.localOptima.size, "the unimodal shortcut runs exactly one local search")
    }

    @Test
    fun degradedConfidenceIntervalIsAFiniteTInterval() {
        val pd = problem()
        val evaluator = IscTestSupport.FunctionEvaluator(pd, ::unimodal)
        val isc = ISCSolver(
            problemDefinition = pd, evaluator = evaluator, streamNum = 2,
            replicationsPerEvaluation = 3, deltaC = 0.0, skipGlobalPhase = true
        )
        isc.runAllIterations()
        val ci = isc.confidenceInterval
        assertTrue(ci.lowerLimit.isFinite() && ci.upperLimit.isFinite(), "the degraded CI must be finite")
        assertTrue(ci.width >= 0.0, "the degraded CI must have non-negative width")
    }

    @Test
    fun fullPipelineReturnsFeasibleBestWithinTheIndifferenceZoneInterval() {
        val pd = problem()
        val evaluator = IscTestSupport.FunctionEvaluator(pd, ::bimodal)
        val sp = RNStreamProvider()
        val nga = NichingGeneticAlgorithmSolver(
            problemDefinition = pd, evaluator = evaluator, streamNum = 3, streamProvider = sp,
            populationSize = 12, maxIterations = 15, replicationsPerEvaluation = 3
        )
        val deltaC = 1.0
        val isc = ISCSolver(
            problemDefinition = pd,
            evaluator = evaluator,
            streamNum = 1,
            streamProvider = sp,
            replicationsPerEvaluation = 3,
            deltaC = deltaC,
            globalPhase = nga,
            localPhaseFactory = { seed ->
                CompassSolver(
                    problemDefinition = pd, evaluator = evaluator, streamNum = 0, streamProvider = sp,
                    deltaL = deltaC, maxIterations = 25, replicationsPerEvaluation = 3
                ).also { it.seed = seed }
            }
        )
        isc.runAllIterations()
        val best = isc.bestSolution
        assertTrue(best.isInputFeasible(), "the selected best must be feasible")
        assertTrue(isc.localOptima.isNotEmpty(), "the local phase must produce at least one local optimum")
        val ci = isc.confidenceInterval
        assertEquals(2.0 * deltaC, ci.width, 1e-9, "the IZ interval width must be 2*deltaC")
        assertTrue(best.estimatedObjFncValue in ci.lowerLimit..ci.upperLimit,
            "the best mean must lie within its reported +/- deltaC interval")
    }

    @Test
    fun drivesTowardAMinimumOnTheBimodalProblem() {
        val pd = problem()
        val evaluator = IscTestSupport.FunctionEvaluator(pd, ::bimodal)
        val sp = RNStreamProvider()
        val nga = NichingGeneticAlgorithmSolver(
            problemDefinition = pd, evaluator = evaluator, streamNum = 3, streamProvider = sp,
            populationSize = 12, maxIterations = 15, replicationsPerEvaluation = 3
        )
        val isc = ISCSolver(
            problemDefinition = pd, evaluator = evaluator, streamNum = 1, streamProvider = sp,
            replicationsPerEvaluation = 3, deltaC = 0.0, globalPhase = nga,
            localPhaseFactory = { seed ->
                CompassSolver(
                    problemDefinition = pd, evaluator = evaluator, streamNum = 0, streamProvider = sp,
                    deltaL = 0.0, maxIterations = 25, replicationsPerEvaluation = 3
                ).also { it.seed = seed }
            }
        )
        isc.runAllIterations()
        assertTrue(isc.bestSolution.estimatedObjFncValue < 1.0,
            "the full ISC pipeline should locate a near-optimal solution (was ${isc.bestSolution.estimatedObjFncValue})")
    }

    @Test
    fun unimodalShortcutIsReproducibleForAFixedStream() {
        fun run(): ksl.simopt.evaluator.Solution {
            val pd = problem()
            val evaluator = IscTestSupport.FunctionEvaluator(pd, ::unimodal)
            val isc = ISCSolver(
                problemDefinition = pd, evaluator = evaluator, streamNum = 5,
                replicationsPerEvaluation = 3, deltaC = 0.0, skipGlobalPhase = true
            )
            isc.runAllIterations()
            return isc.bestSolution
        }
        assertEquals(run().inputMap, run().inputMap, "the same stream number must reproduce the same best inputs")
    }
}
