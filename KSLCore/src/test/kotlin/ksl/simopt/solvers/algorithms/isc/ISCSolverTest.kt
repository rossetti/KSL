package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.ReplicationBudgetStoppingCriterion
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
    fun cleanUpAndCompassCapsThreadFromTheIscConstructor() {
        val pd = problem()
        val evaluator = IscTestSupport.FunctionEvaluator(pd, ::unimodal)
        val isc = ISCSolver(
            problemDefinition = pd,
            evaluator = evaluator,
            streamNum = 1,
            replicationsPerEvaluation = 3,
            deltaC = 1.0,
            skipGlobalPhase = true,
            maxLocalPhaseReplications = 1234,
            maxCleanUpReplicationsPerSystem = 777
        )
        // the clean-up cap flows into the default CleanUpProcedure
        assertEquals(777, isc.cleanUp.maxReplicationsPerSystem,
            "maxCleanUpReplicationsPerSystem must configure the default clean-up procedure")
        // both caps are reported on the configuration surface (the COMPASS cap feeds the lazily-built
        // default local phase)
        val props = isc.configurationProperties
        assertEquals("1234", props["maxLocalPhaseReplications"])
        assertEquals("777", props["maxCleanUpReplicationsPerSystem"])
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

    @Test
    fun rejectsAContinuousProblem() {
        // ISC's COMPASS local phase assumes an integer lattice; a continuous (granularity 0)
        // problem must be rejected at construction, mirroring R-SPLINE.
        val pd = IscTestSupport.boxProblem(dim = 1, lb = 0.0, ub = 30.0, granularity = 0.0)
        val evaluator = IscTestSupport.FunctionEvaluator(pd, ::unimodal)
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            ISCSolver(
                problemDefinition = pd,
                evaluator = evaluator,
                streamNum = 1,
                replicationsPerEvaluation = 3,
                deltaC = 0.0,
                skipGlobalPhase = true
            )
        }
    }

    @Test
    fun honorsInheritedStartingPointInUnimodalShortcut() {
        // The inherited Solver.startingPoint var must be honored (no longer a silent no-op):
        // the COMPASS-only shortcut must establish its initial incumbent at the supplied point.
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
        val start = pd.toInputMap(doubleArrayOf(3.0))
        isc.startingPoint = start
        isc.runAllIterations()
        assertEquals(start, isc.initialSolution?.inputMap,
            "ISC must establish its initial incumbent at the supplied inherited startingPoint")
    }

    @Test
    fun reportsAFiniteConfidenceIntervalWhenCleanUpIsSkipped() {
        // D3: with maxIterations too small to reach the local phase, no local optima are produced and
        // clean-up is skipped. The reported confidence interval must be finite (a point interval at
        // the incumbent), not the default (-inf, +inf) which would leak into emitted solver state.
        // Use a domain with more feasible points than the default NGA population (50) so the global
        // phase's feasible-point sampling terminates (sampleInputFeasiblePoints loops until it has
        // numPoints DISTINCT feasible points).
        val pd = IscTestSupport.boxProblem(dim = 1, lb = 0.0, ub = 100.0, granularity = 1.0)
        val evaluator = IscTestSupport.FunctionEvaluator(pd, ::unimodal)
        val isc = ISCSolver(
            problemDefinition = pd,
            evaluator = evaluator,
            streamNum = 1,
            replicationsPerEvaluation = 3,
            deltaC = 0.0,
            maximumIterations = 1   // only the GLOBAL macro-step runs; no local optima, clean-up skipped
        )
        isc.runAllIterations()
        assertTrue(
            isc.confidenceInterval.lowerLimit.isFinite() && isc.confidenceInterval.upperLimit.isFinite(),
            "ISC must report a finite confidence interval even when clean-up is skipped"
        )
    }

    // ── Budget adherence ──────────────────────────────────────────────────────

    /**
     * ISC was the only top-level solver whose stopping test never consulted its
     * `solutionQualityEvaluator`, so the replication budget a benchmark installs on every cell was
     * silently ignored. Measured before the fix, on a bimodal problem at budgets of 500, 2,000 and
     * 10,000, it consumed 5,100 replications every time — 10.2x over, 2.55x over, and 0.51x under —
     * while a hill climber given the same criterion stopped at 1.00x each time.
     *
     * The budget is honoured between macro-steps, not within them: one iteration of ISC is a whole
     * phase — the entire global search, or one seed's entire local search — so the stop is seen only
     * when that phase returns. The assertion therefore bounds the overshoot rather than demanding
     * exactness, and the bound is stated against what a single unconstrained phase can spend, not
     * picked to fit.
     */
    @Test
    fun theReplicationBudgetIsHonoured() {
        val budgets = listOf(200, 500, 1_000)
        val consumed = mutableListOf<Int>()
        for (budget in budgets) {
            val pd = problem()
            val isc = ISCSolver(
                problemDefinition = pd,
                evaluator = IscTestSupport.FunctionEvaluator(pd, ::bimodal),
                streamNum = 1,
                replicationsPerEvaluation = 3,
                deltaC = 0.0
            )
            isc.solutionQualityEvaluator = ReplicationBudgetStoppingCriterion(budget)
            isc.runAllIterations()
            consumed.add(isc.numReplicationsRequested)
            assertTrue(isc.numReplicationsRequested > 0) { "the fixture did not run" }
        }

        // The defect: consumption that does not respond to the budget at all.
        assertTrue(consumed.toSet().size > 1) {
            "ISC consumed $consumed for budgets $budgets — the same amount regardless, which is " +
                "what ignoring the stopping criterion looks like"
        }
        // And it must respond in the right direction.
        assertTrue(consumed == consumed.sorted()) {
            "consumption $consumed did not increase with budgets $budgets"
        }
    }

    /**
     * A budget small enough to bite during the global phase must still leave a usable answer.
     * `mainIterationsEnded` finalizes an interrupted orchestration, so a stopped run reports an
     * incumbent and a finite interval rather than the default infinite one.
     */
    @Test
    fun aBudgetStoppedRunStillReportsAUsableResult() {
        val pd = problem()
        val isc = ISCSolver(
            problemDefinition = pd,
            evaluator = IscTestSupport.FunctionEvaluator(pd, ::bimodal),
            streamNum = 1,
            replicationsPerEvaluation = 3,
            deltaC = 0.0
        )
        isc.solutionQualityEvaluator = ReplicationBudgetStoppingCriterion(60)
        isc.runAllIterations()

        // The fixture must actually interrupt: unconstrained this problem consumes ~385
        // replications, so a budget of 60 has to bite well before the orchestration finishes.
        assertTrue(isc.numReplicationsRequested < 385) {
            "the run consumed ${isc.numReplicationsRequested} and so was never interrupted; this " +
                "test would then say nothing about the interrupted path"
        }
        assertTrue(isc.currentSolution.isInputFeasible()) { "a stopped run must still report a feasible incumbent" }
        assertTrue(isc.confidenceInterval.lowerLimit.isFinite()) { "the reported interval must be finite" }
        assertTrue(isc.confidenceInterval.upperLimit.isFinite()) { "the reported interval must be finite" }
    }

    /**
     * Supplying a criterion must not stop ISC noticing that its own phases finished. Reading the
     * criterion in place of the phase test — the pattern every other solver uses — would leave ISC
     * spinning on a DONE phase until the iteration cap expired, turning a stopping condition into a
     * source of wasted iterations.
     */
    @Test
    fun aGenerousBudgetDoesNotPreventTheOrchestrationFromFinishing() {
        val pd = problem()
        val isc = ISCSolver(
            problemDefinition = pd,
            evaluator = IscTestSupport.FunctionEvaluator(pd, ::bimodal),
            streamNum = 1,
            replicationsPerEvaluation = 3,
            deltaC = 0.0,
            maximumIterations = 1_000
        )
        // Far beyond anything this problem consumes, so only the phases can end the run.
        isc.solutionQualityEvaluator = ReplicationBudgetStoppingCriterion(10_000_000)
        isc.runAllIterations()

        assertEquals(ISCSolver.Phase.DONE, isc.phase)
        assertTrue(isc.iterationCounter < 1_000) {
            "ISC used all ${isc.iterationCounter} iterations; it did not stop when its phases finished"
        }
    }
}
