package ksl.simopt.evaluator

import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * One replication per evaluation is explicitly legal -- `FixedReplicationsPerEvaluation` requires
 * only that the count be positive -- and it is a reasonable thing to ask for when evaluations are
 * expensive and the search is meant to move quickly on noisy information.
 *
 * A response estimate built from one observation has no sample variance, so no confidence
 * interval can be formed for it. Testing a response constraint at that estimate is therefore not
 * possible, and the right answer is that the constraint has not been shown to hold -- not an
 * exception. It was an exception: the interval routine required at least two observations, and
 * the default ranking of solutions goes straight through it, because `bestSolution` ranks
 * feasibility first and feasibility is decided by exactly this test.
 *
 * The failure is therefore in the ordinary path of an ordinary configuration, and it lands when
 * the run asks for its answer rather than while it is searching.
 */
@Timeout(120)
class SingleObservationFeasibilityTest {

    private companion object {
        const val MODEL_ID = "singleObservation"
        const val OBJ = "objFn"
        const val CONSTRAINED = "usage"
    }

    private fun makeProblem(): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = "singleObservation",
            modelIdentifier = MODEL_ID,
            objFnResponseName = OBJ,
            inputNames = listOf("x"),
            responseNames = listOf(CONSTRAINED)
        )
        pd.inputVariable("x", 0.0, 10.0, granularity = 1.0)
        pd.responseConstraint(CONSTRAINED, rhsValue = 5.0, inequalityType = InequalityType.LESS_THAN)
        return pd
    }

    private fun makeEvaluator(pd: ProblemDefinition): EvaluatorIfc {
        val oracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ, CONSTRAINED),
            ResponseFunctionBuilderIfc { streamProvider ->
                val stream = streamProvider.rnStream(1)
                ResponseFunctionIfc { inputs ->
                    val x = inputs.getValue("x")
                    mapOf(OBJ to x + stream.randU01(), CONSTRAINED to x)
                }
            }
        )
        return Evaluator(pd, oracle)
    }

    /** Evaluates one design point at the given number of replications. */
    private fun solutionAt(pd: ProblemDefinition, evaluator: EvaluatorIfc, x: Double, replications: Int): Solution {
        val request = EvaluationRequest(
            modelIdentifier = pd.modelIdentifier,
            modelInputs = listOf(
                ModelInputs(pd.modelIdentifier, replications, pd.toInputMap(mutableMapOf("x" to x)), pd.allResponseNames.toSet())
            )
        )
        return evaluator.evaluate(request).values.first()
    }

    @Test
    @DisplayName("A solution built from one observation can be tested for feasibility")
    fun singleObservationSolutionCanBeRanked() {
        val pd = makeProblem()
        val evaluator = makeEvaluator(pd)
        val solution = solutionAt(pd, evaluator, x = 2.0, replications = 1)
        assertEquals(1.0, solution.responseEstimatesMap.getValue(CONSTRAINED).count)

        // One observation carries no information about sampling error, so the constraint has not
        // been shown to hold. Not shown is the answer; throwing is not.
        assertFalse(solution.isResponseConstraintFeasible()) {
            "a single-observation estimate was reported as demonstrably feasible"
        }
    }

    @Test
    @DisplayName("Comparing solutions feasibility-first works at one replication per evaluation")
    fun feasibilityFirstComparatorHandlesSingleObservations() {
        val pd = makeProblem()
        val evaluator = makeEvaluator(pd)
        val comfortablyInside = solutionAt(pd, evaluator, x = 1.0, replications = 1)
        val wellOutside = solutionAt(pd, evaluator, x = 9.0, replications = 1)

        // this is the comparator bestSolution uses
        val comparator = FeasibilityFirstComparator()
        assertNotNull(comparator.compare(comfortablyInside, wellOutside))
        assertEquals(
            -comparator.compare(comfortablyInside, wellOutside),
            comparator.compare(wellOutside, comfortablyInside)
        )
    }

    @Test
    @DisplayName("A solver running one replication per evaluation can report its best solution")
    fun solverAtOneReplicationReportsABestSolution() {
        val pd = makeProblem()
        val solver = StochasticHillClimber(
            pd, makeEvaluator(pd),
            maximumIterations = 25,
            replicationsPerEvaluation = 1,
            streamNum = 1
        )
        solver.runAllIterations()

        // bestSolution ranks feasibility first, so it is the accessor that goes through the test
        val best = solver.bestSolution
        assertTrue(best.estimatedObjFncValue.isFinite())
        assertEquals(1.0, best.responseEstimatesMap.getValue(CONSTRAINED).count)
    }

    @Test
    @DisplayName("Two observations still produce a real interval")
    fun twoObservationsStillIntervalled() {
        // the degradation must not swallow the case it was added beside: with two observations
        // there is a variance and the ordinary interval applies
        val pd = makeProblem()
        val evaluator = makeEvaluator(pd)
        val inside = solutionAt(pd, evaluator, x = 0.0, replications = 20)
        assertTrue(inside.isResponseConstraintFeasible()) {
            "a point far inside the region was not judged feasible at 20 observations"
        }
        val outside = solutionAt(pd, evaluator, x = 10.0, replications = 20)
        assertFalse(outside.isResponseConstraintFeasible())
    }
}
