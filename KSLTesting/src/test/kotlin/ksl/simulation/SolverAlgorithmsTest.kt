package ksl.simulation

import ksl.examples.general.simopt.BuildLKModel
import ksl.examples.general.simopt.makeLKInventoryModelProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.StochasticSolver
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the four SimOpt solver algorithms (SHC, SA, CE, R-SPLINE).
 *
 * Model: LKInventoryModel — minimise E[TotalCost] over
 *   orderQuantity ∈ [1, 100] (integer), reorderPoint ∈ [1, 100] (integer).
 * No response constraints (unconstrained minimisation).
 *
 * Two groups:
 *
 * 1. **Fast smoke tests** — tiny config (maxIterations=2, replicationsPerEval=5).
 *    The @BeforeAll runs all four solvers once; individual tests verify post-run
 *    invariants (isValid, input-feasible, finite average, iteration count).
 *    These run in CI by default.
 *
 * 2. **Slow convergence tests** — @Tag("slow"), excluded from default CI.
 *    Each test runs its own solver for 50 iterations with 20 reps/eval and
 *    verifies the best solution is within bounds and has a positive finite cost.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SolverAlgorithmsTest {

    // ── Configuration ─────────────────────────────────────────────────────────

    companion object {
        private const val FAST_MAX_ITERS    = 2
        private const val FAST_REPS_PER_EVAL = 5

        private const val SLOW_MAX_ITERS    = 50
        private const val SLOW_REPS_PER_EVAL = 20
    }

    // ── Shared fast-solver state ──────────────────────────────────────────────

    private lateinit var shcFast: StochasticSolver
    private lateinit var saFast:  StochasticSolver
    private lateinit var ceFast:  StochasticSolver
    private lateinit var rsFast:  StochasticSolver

    @BeforeAll
    fun runFastSolvers() {
        val pd = makeLKInventoryModelProblemDefinition()

        shcFast = Solver.createStochasticHillClimbingSolver(
            problemDefinition        = pd,
            modelBuilder             = BuildLKModel,
            maxIterations            = FAST_MAX_ITERS,
            replicationsPerEvaluation = FAST_REPS_PER_EVAL,
            defaultKSLDatabaseObserverOption = false
        )
        shcFast.runAllIterations()

        saFast = Solver.createSimulatedAnnealingSolver(
            problemDefinition        = pd,
            modelBuilder             = BuildLKModel,
            maxIterations            = FAST_MAX_ITERS,
            replicationsPerEvaluation = FAST_REPS_PER_EVAL,
            defaultKSLDatabaseObserverOption = false
        )
        saFast.runAllIterations()

        ceFast = Solver.createCrossEntropySolver(
            problemDefinition        = pd,
            modelBuilder             = BuildLKModel,
            maxIterations            = FAST_MAX_ITERS,
            replicationsPerEvaluation = FAST_REPS_PER_EVAL,
            defaultKSLDatabaseObserverOption = false
        )
        ceFast.runAllIterations()

        rsFast = Solver.createRsplineSolver(
            problemDefinition = pd,
            modelBuilder      = BuildLKModel,
            maxIterations     = FAST_MAX_ITERS,
            defaultKSLDatabaseObserverOption = false
        )
        rsFast.runAllIterations()
    }

    // ── Group 1: Fast smoke — SHC ────────────────────────────────────────────

    @Test
    fun shcBestSolutionIsValid() {
        assertTrue(shcFast.bestSolution.isValid, "SHC bestSolution must be valid after run")
    }

    @Test
    fun shcBestSolutionIsInputFeasible() {
        assertTrue(shcFast.bestSolution.isInputFeasible(),
            "SHC bestSolution must be within variable bounds")
    }

    @Test
    fun shcBestSolutionAverageIsFinite() {
        assertTrue(shcFast.bestSolution.average.isFinite(),
            "SHC bestSolution TotalCost must be a finite number")
    }

    @Test
    fun shcIterationCountEqualsMaxIterations() {
        assertEquals(FAST_MAX_ITERS, shcFast.iterationCounter,
            "SHC must complete exactly $FAST_MAX_ITERS iterations")
    }

    // ── Group 1: Fast smoke — SA ─────────────────────────────────────────────

    @Test
    fun saBestSolutionIsValid() {
        assertTrue(saFast.bestSolution.isValid, "SA bestSolution must be valid after run")
    }

    @Test
    fun saBestSolutionIsInputFeasible() {
        assertTrue(saFast.bestSolution.isInputFeasible(),
            "SA bestSolution must be within variable bounds")
    }

    @Test
    fun saBestSolutionAverageIsFinite() {
        assertTrue(saFast.bestSolution.average.isFinite(),
            "SA bestSolution TotalCost must be a finite number")
    }

    @Test
    fun saIterationCountEqualsMaxIterations() {
        assertEquals(FAST_MAX_ITERS, saFast.iterationCounter,
            "SA must complete exactly $FAST_MAX_ITERS iterations")
    }

    // ── Group 1: Fast smoke — CE ─────────────────────────────────────────────

    @Test
    fun ceBestSolutionIsValid() {
        assertTrue(ceFast.bestSolution.isValid, "CE bestSolution must be valid after run")
    }

    @Test
    fun ceBestSolutionIsInputFeasible() {
        assertTrue(ceFast.bestSolution.isInputFeasible(),
            "CE bestSolution must be within variable bounds")
    }

    @Test
    fun ceBestSolutionAverageIsFinite() {
        assertTrue(ceFast.bestSolution.average.isFinite(),
            "CE bestSolution TotalCost must be a finite number")
    }

    @Test
    fun ceIterationCountEqualsMaxIterations() {
        assertEquals(FAST_MAX_ITERS, ceFast.iterationCounter,
            "CE must complete exactly $FAST_MAX_ITERS iterations")
    }

    // ── Group 1: Fast smoke — R-SPLINE ───────────────────────────────────────

    @Test
    fun rsplineBestSolutionIsValid() {
        assertTrue(rsFast.bestSolution.isValid, "R-SPLINE bestSolution must be valid after run")
    }

    @Test
    fun rsplineBestSolutionIsInputFeasible() {
        assertTrue(rsFast.bestSolution.isInputFeasible(),
            "R-SPLINE bestSolution must be within variable bounds")
    }

    @Test
    fun rsplineBestSolutionAverageIsFinite() {
        assertTrue(rsFast.bestSolution.average.isFinite(),
            "R-SPLINE bestSolution TotalCost must be a finite number")
    }

    @Test
    fun rsplineIterationCountEqualsMaxIterations() {
        assertEquals(FAST_MAX_ITERS, rsFast.iterationCounter,
            "R-SPLINE must complete exactly $FAST_MAX_ITERS iterations")
    }

    // ── Group 2: Slow convergence — SHC ──────────────────────────────────────

    @Test
    @Tag("slow")
    fun shcSlowBestSolutionIsFeasibleAfterManyIterations() {
        val pd = makeLKInventoryModelProblemDefinition()
        val solver = Solver.createStochasticHillClimbingSolver(
            problemDefinition        = pd,
            modelBuilder             = BuildLKModel,
            maxIterations            = SLOW_MAX_ITERS,
            replicationsPerEvaluation = SLOW_REPS_PER_EVAL,
            defaultKSLDatabaseObserverOption = false
        )
        solver.runAllIterations()
        assertTrue(solver.bestSolution.isValid)
        assertTrue(solver.bestSolution.isInputFeasible())
        assertTrue(solver.bestSolution.average.isFinite() && solver.bestSolution.average > 0.0,
            "TotalCost must be positive after $SLOW_MAX_ITERS SHC iterations")
    }

    @Test
    @Tag("slow")
    fun saSlowBestSolutionIsFeasibleAfterManyIterations() {
        val pd = makeLKInventoryModelProblemDefinition()
        val solver = Solver.createSimulatedAnnealingSolver(
            problemDefinition        = pd,
            modelBuilder             = BuildLKModel,
            maxIterations            = SLOW_MAX_ITERS,
            replicationsPerEvaluation = SLOW_REPS_PER_EVAL,
            defaultKSLDatabaseObserverOption = false
        )
        solver.runAllIterations()
        assertTrue(solver.bestSolution.isValid)
        assertTrue(solver.bestSolution.isInputFeasible())
        assertTrue(solver.bestSolution.average.isFinite() && solver.bestSolution.average > 0.0,
            "TotalCost must be positive after $SLOW_MAX_ITERS SA iterations")
    }

    @Test
    @Tag("slow")
    fun ceSlowBestSolutionIsFeasibleAfterManyIterations() {
        val pd = makeLKInventoryModelProblemDefinition()
        val solver = Solver.createCrossEntropySolver(
            problemDefinition        = pd,
            modelBuilder             = BuildLKModel,
            maxIterations            = SLOW_MAX_ITERS,
            replicationsPerEvaluation = SLOW_REPS_PER_EVAL,
            defaultKSLDatabaseObserverOption = false
        )
        solver.runAllIterations()
        assertTrue(solver.bestSolution.isValid)
        assertTrue(solver.bestSolution.isInputFeasible())
        assertTrue(solver.bestSolution.average.isFinite() && solver.bestSolution.average > 0.0,
            "TotalCost must be positive after $SLOW_MAX_ITERS CE iterations")
    }

    @Test
    @Tag("slow")
    fun rsplineSlowBestSolutionIsFeasibleAfterManyIterations() {
        val pd = makeLKInventoryModelProblemDefinition()
        val solver = Solver.createRsplineSolver(
            problemDefinition = pd,
            modelBuilder      = BuildLKModel,
            maxIterations     = SLOW_MAX_ITERS,
            defaultKSLDatabaseObserverOption = false
        )
        solver.runAllIterations()
        assertTrue(solver.bestSolution.isValid)
        assertTrue(solver.bestSolution.isInputFeasible())
        assertTrue(solver.bestSolution.average.isFinite() && solver.bestSolution.average > 0.0,
            "TotalCost must be positive after $SLOW_MAX_ITERS R-SPLINE iterations")
    }
}
