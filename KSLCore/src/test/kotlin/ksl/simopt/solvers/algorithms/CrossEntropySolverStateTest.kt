package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.evaluator.ResponseFunctionOracle
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.SolverStateSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Cross-entropy was the one solver contributing nothing to the iteration trace: every other
 * algorithm overrides `extractSolverSpecificState`, so a study capturing solver state got
 * `swarmDiameter` from PSO and `diversity` from GA while CE emitted nothing at all — and the
 * conclusion that CE collapses prematurely had to be inferred from identical results across a
 * ninefold budget increase, rather than measured.
 *
 * The quantity that settles it is the sampling distribution's coefficient of variation, which is
 * exactly what the sampler's own convergence test thresholds. These tests pin that the keys are
 * emitted, that they are emitted even before the first elite sample exists, and that the CV
 * actually falls as the reference distribution concentrates.
 */
@Timeout(120)
class CrossEntropySolverStateTest {

    private companion object {
        const val MODEL_ID = "ceStateProbe"
        const val OBJ = "y"
        val EXPECTED_KEYS = setOf("eliteCount", "eliteSpread", "samplerMeanCV")
    }

    /** A noiseless quadratic with its optimum inside the box, so the sampler has something to converge onto. */
    private fun problem(): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = "ceStateProbe",
            modelIdentifier = MODEL_ID,
            objFnResponseName = OBJ,
            inputNames = listOf("x1", "x2")
        )
        pd.inputVariable("x1", lowerBound = -10.0, upperBound = 10.0)
        pd.inputVariable("x2", lowerBound = -10.0, upperBound = 10.0)
        return pd
    }

    private fun evaluator(pd: ProblemDefinition): EvaluatorIfc {
        val oracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ),
            ResponseFunctionBuilderIfc { _ ->
                ResponseFunctionIfc { inputs ->
                    val x1 = inputs.getValue("x1")
                    val x2 = inputs.getValue("x2")
                    mapOf(OBJ to (x1 - 2.0) * (x1 - 2.0) + (x2 + 3.0) * (x2 + 3.0))
                }
            }
        )
        return Evaluator(pd, oracle)
    }

    /** Runs a short CE search, collecting one snapshot per iteration. */
    private fun snapshotsFromARun(maxIterations: Int = 25): List<SolverStateSnapshot> {
        val pd = problem()
        val solver = CrossEntropySolver(
            problemDefinition = pd,
            evaluator = evaluator(pd),
            streamNum = 1,
            maximumIterations = maxIterations
        )
        val captured = mutableListOf<SolverStateSnapshot>()
        solver.snapShotFrequency = 1
        solver.iterationEmitter.attach { captured.add(it) }
        solver.runAllIterations()
        return captured
    }

    @Test
    @DisplayName("Cross-entropy contributes solver-specific state to every iteration snapshot")
    fun crossEntropyEmitsSolverSpecificState() {
        val snapshots = snapshotsFromARun()
        assertTrue(snapshots.isNotEmpty()) { "the fixture produced no snapshots" }
        for (snapshot in snapshots) {
            val state = snapshot.solverSpecificState
            assertNotNull(state) { "iteration ${snapshot.iterationNumber} emitted no solver state" }
            assertEquals(EXPECTED_KEYS, state!!.keys) {
                "the key set must be stable across iterations so the long-format state table does " +
                    "not gain and lose columns mid-run"
            }
        }
    }

    @Test
    @DisplayName("The elite count is positive once the search has sampled")
    fun eliteCountIsPositiveDuringTheSearch() {
        val snapshots = snapshotsFromARun()
        val counts = snapshots.map { it.solverSpecificState!!.getValue("eliteCount") }
        assertTrue(counts.any { it > 0.0 }) { "no iteration reported any elites: $counts" }
    }

    /**
     * The measurement the whole item exists for. The reference distribution concentrating onto a
     * point IS premature convergence, and the mean CV is the direct reading of it.
     */
    @Test
    @DisplayName("The sampler's mean coefficient of variation falls as the reference distribution concentrates")
    fun samplerMeanCoefficientOfVariationFallsAsTheSearchConverges() {
        val cvs = snapshotsFromARun()
            .map { it.solverSpecificState!!.getValue("samplerMeanCV") }
            .filter { it.isFinite() }
        assertTrue(cvs.size >= 2) { "not enough finite CV readings to compare: $cvs" }
        assertTrue(cvs.last() < cvs.first()) {
            "the sampling distribution did not concentrate: first=${cvs.first()} last=${cvs.last()}"
        }
    }

    /**
     * The empty-elites branch. Iteration 0 is the initialized state, at which point the elite list
     * has been cleared and no sample has been drawn — so the state hook is reachable before any
     * elites exist and must still answer with the full key set rather than throwing or returning a
     * short map. A solver whose state hook is only safe mid-search silently drops the first row of
     * every trace.
     */
    @Test
    @DisplayName("Solver state is well-formed at the initialized state, before any elites exist")
    fun stateIsWellFormedBeforeAnyElitesExist() {
        val initial = snapshotsFromARun(maxIterations = 3).firstOrNull { it.iterationNumber == 0 }
        assertNotNull(initial) { "no snapshot was emitted for the initialized state" }
        val state = initial!!.solverSpecificState
        assertNotNull(state)
        assertEquals(EXPECTED_KEYS, state!!.keys)
        assertEquals(0.0, state.getValue("eliteCount"))
        assertTrue(state.getValue("eliteSpread").isNaN()) {
            "with no elites there is no spread to report; NaN is the honest answer"
        }
    }
}
