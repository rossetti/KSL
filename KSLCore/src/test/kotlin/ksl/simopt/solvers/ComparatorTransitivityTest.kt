package ksl.simopt.solvers

import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.evaluator.ResponseFunctionOracle
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * `Solver.compare` judges two penalized solutions at the LATER of their two evaluation numbers,
 * so that a rising penalty multiplier applies equally to both sides of a comparison. That is
 * correct for a comparison taken by itself, and it is what stops an incumbent being defended by
 * a smaller multiplier than its challengers carry.
 *
 * It is not a total order, and it must not be used as one. Each PAIR is judged at its own clock,
 * so three solutions can be compared at three different multipliers and the ordering they imply
 * can contain a cycle. A comparator with a cycle breaks the contract sorting requires, and the
 * result is undefined: it may throw, or it may silently return an order the comparator itself
 * disagrees with.
 *
 * That mattered because several solvers SORT rather than only comparing pairs — the genetic
 * algorithm orders its population every generation, rank selection ranks it, the niching variant
 * does both, and Bayesian optimization trims its archive. Those call sites now go through
 * `orderedBestFirst`, which fixes one clock for the whole ordering.
 *
 * These tests hold both halves in place: the pairwise rule may still contain cycles, because
 * that is inherent to judging each pair at its own clock and is harmless when used pairwise;
 * the ORDERING must contain none.
 *
 * The cycles need solutions whose penalties grow at different rates and whose clocks differ,
 * which is why the defect surfaced on a problem with a DETERMINISTIC objective: when many
 * solutions share an objective, ordering is decided by the penalty term alone and the clock
 * difference dominates. Noise hides it — which is why a grid of noisy synthetic problems did not
 * catch this and one deterministic problem did.
 */
@Timeout(120)
class ComparatorTransitivityTest {

    private companion object {
        const val MODEL_ID = "transitivityProbe"
        const val OBJ = "objFn"
        const val USAGE = "usage"

        /**
         * A cycle by construction. With a penalized value of `objective + M0 * k * violation`:
         *
         *     a: objective 0, penalty rate 1,   clock 1
         *     b: objective 2, penalty rate 0,   clock 1
         *     c: objective 0, penalty rate 0.5, clock 100
         *
         *     compare(a, b) at k = 1   ->  1 vs 2    ->  a < b
         *     compare(b, c) at k = 100 ->  2 vs 50   ->  b < c
         *     compare(a, c) at k = 100 -> 100 vs 50  ->  c < a
         */
        const val CYCLE_LOW_CLOCK = 1
        const val CYCLE_HIGH_CLOCK = 100
    }

    /**
     * A problem whose objective is a function of the input alone -- no noise -- and whose single
     * response constraint is violated in proportion to the input. Both the objective and the
     * violation are therefore controllable exactly, which is what lets a cycle be built rather
     * than hunted for.
     */
    private fun makeProblem(): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = "transitivityProbe",
            modelIdentifier = MODEL_ID,
            objFnResponseName = OBJ,
            inputNames = listOf("objective", "violation"),
            responseNames = listOf(USAGE)
        )
        pd.inputVariable("objective", 0.0, 1000.0)
        pd.inputVariable("violation", 0.0, 1000.0)
        pd.responseConstraint(USAGE, rhsValue = 0.0, inequalityType = InequalityType.LESS_THAN)
        return pd
    }

    private fun makeEvaluator(pd: ProblemDefinition): EvaluatorIfc {
        val oracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ, USAGE),
            ResponseFunctionBuilderIfc { _ ->
                ResponseFunctionIfc { inputs ->
                    mapOf(
                        OBJ to inputs.getValue("objective"),
                        USAGE to inputs.getValue("violation")
                    )
                }
            }
        )
        return Evaluator(pd, oracle)
    }

    /** Evaluates one design point and stamps the resulting solution at the given clock. */
    private fun solutionAt(
        pd: ProblemDefinition,
        evaluator: EvaluatorIfc,
        objective: Double,
        violation: Double,
        clock: Int
    ): Solution {
        val inputs = pd.toInputMap(
            mutableMapOf("objective" to objective, "violation" to violation)
        )
        val request = EvaluationRequest(
            modelIdentifier = pd.modelIdentifier,
            modelInputs = listOf(ModelInputs(pd.modelIdentifier, 2, inputs, pd.allResponseNames.toSet()))
        )
        return evaluator.evaluate(request).values.first().atEvaluation(clock)
    }

    @Test
    @DisplayName("compare is not transitive, which is why it must not be sorted with")
    fun compareCanFormACycle() {
        val pd = makeProblem()
        val evaluator = makeEvaluator(pd)
        val solver = StochasticHillClimber(pd, evaluator, replicationsPerEvaluation = 2)

        // rates chosen so the penalty ordering flips between the low and the high clock
        val a = solutionAt(pd, evaluator, objective = 0.0, violation = 0.01, clock = CYCLE_LOW_CLOCK)
        val b = solutionAt(pd, evaluator, objective = 2.0, violation = 0.0, clock = CYCLE_LOW_CLOCK)
        val c = solutionAt(pd, evaluator, objective = 0.0, violation = 0.005, clock = CYCLE_HIGH_CLOCK)

        val ab = solver.compare(a, b)
        val bc = solver.compare(b, c)
        val ac = solver.compare(a, c)

        // a < b and b < c, so a total order requires a < c. It reports c < a.
        assertTrue(ab < 0) { "expected a < b, got $ab" }
        assertTrue(bc < 0) { "expected b < c, got $bc" }
        assertTrue(ac > 0) {
            "expected the cycle to close with c < a, got $ac -- if this fails the fixture no " +
                "longer builds a cycle and the other assertions below prove nothing"
        }
    }

    /** Counts ordered triples (x, y, z) for which the comparator says x < y < z < x. */
    private fun countCycles(solutions: List<Solution>, compare: (Solution, Solution) -> Int): Int {
        var cycles = 0
        for (x in solutions) for (y in solutions) for (z in solutions) {
            if (compare(x, y) < 0 && compare(y, z) < 0 && compare(z, x) < 0) cycles++
        }
        return cycles
    }

    @Test
    @DisplayName("The ordering comparator has no cycles, at any clock")
    fun orderingComparatorIsATotalOrder() {
        val pd = makeProblem()
        val evaluator = makeEvaluator(pd)
        val solver = StochasticHillClimber(pd, evaluator, replicationsPerEvaluation = 2)

        // An ordinary spread: a range of objectives and violations, evaluated at the range of
        // clocks a search accumulates. Nothing here is contrived toward a cycle.
        val solutions = buildList {
            for (clock in listOf(1, 10, 100)) {
                for (objective in listOf(0.0, 1.0, 2.0, 5.0)) {
                    for (violation in listOf(0.0, 0.005, 0.01)) {
                        add(solutionAt(pd, evaluator, objective, violation, clock))
                    }
                }
            }
        }

        // The pairwise rule still contains cycles, and that is expected: it is what judging each
        // pair at its own clock means. It is safe only because nothing sorts with it any more.
        val pairwiseCycles = countCycles(solutions) { x, y -> solver.compare(x, y) }
        println("Solver.compare (pairwise): $pairwiseCycles cyclic triples among ${solutions.size}")

        // The ORDERING comparator must have none, at any clock a caller might choose.
        for (clock in listOf(1, 10, 100, solutions.maxOf { it.evaluationNumber })) {
            val orderingCycles = countCycles(solutions) { x, y ->
                solver.comparatorAt(clock).compare(x, y)
            }
            assertEquals(0, orderingCycles) {
                "comparatorAt($clock) produced $orderingCycles cyclic triples; it is used to sort"
            }
        }
    }

    @Test
    @DisplayName("orderedBestFirst sorts cleanly and returns the original solutions")
    fun orderedBestFirstSortsCleanly() {
        val pd = makeProblem()
        val evaluator = makeEvaluator(pd)

        val solutions = buildList {
            repeat(16) { i ->
                add(solutionAt(pd, evaluator, objective = i.toDouble(), violation = 0.01, clock = CYCLE_LOW_CLOCK))
                add(solutionAt(pd, evaluator, objective = i.toDouble(), violation = 0.005, clock = CYCLE_HIGH_CLOCK))
            }
        }
        val solver = StochasticHillClimber(pd, evaluator, replicationsPerEvaluation = 2)
        val sorted = solver.orderedBestFirst(solutions)
        val clock = solutions.maxOf { it.evaluationNumber }

        assertEquals(solutions.size, sorted.size)
        // ordered at the shared clock, best first
        for (i in 1 until sorted.size) {
            assertTrue(
                sorted[i - 1].atEvaluation(clock).penalizedObjFncValue <=
                    sorted[i].atEvaluation(clock).penalizedObjFncValue
            )
        }
        // and the ORIGINALS come back: a solution's evaluation number takes part in its equality,
        // so returning restamped copies would break a caller matching against one it already holds
        assertEquals(solutions.toSet(), sorted.toSet())
    }
}
