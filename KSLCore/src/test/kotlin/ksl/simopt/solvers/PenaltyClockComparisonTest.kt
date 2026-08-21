package ksl.simopt.solvers

import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.evaluator.ResponseFunctionOracle
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.SimulatedAnnealing
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * A dynamic penalty multiplies a constraint violation by a factor that grows with the evaluation
 * number, so that a search is free to roam infeasible early and is pushed to feasibility later.
 * The multiplier is therefore a property of WHEN a comparison is made -- not of when either
 * solution happened to be evaluated.
 *
 * Comparing an incumbent at the clock it was born with against a challenger at the current one
 * penalizes the challenger harder for the same violation, by the ratio of the two clocks. That
 * is a ratchet, and it is self-reinforcing: while the incumbent stands its clock does not
 * advance, so the bar rises against every later candidate and MORE effort makes it worse. A
 * search started outside the feasible region can be frozen on its starting point indefinitely.
 *
 * The fixture below is built so that no single move can reach feasibility. Ten coordinates start
 * at their upper bound and their sum must come down to a limit that takes many accepted moves to
 * reach, while the objective is flat so that violation alone decides. That is what makes the
 * clock visible: each move offers a genuine but partial improvement, which is exactly what a
 * ratchet rejects.
 */
@Timeout(120)
class PenaltyClockComparisonTest {

    private companion object {
        const val MODEL_ID = "clockProbeFn"
        const val OBJ = "objFn"
        const val LOAD = "load"
        const val DIMENSION = 10
        const val UPPER = 10.0
        const val LIMIT = 30.0
    }

    private fun inputNames() = (1..DIMENSION).map { "x$it" }

    /**
     * Minimize a flat objective subject to the coordinate sum being at or below [LIMIT]. Starting
     * every coordinate at [UPPER] gives a sum of 100 against a limit of 30, and one coordinate
     * can be reduced by at most 10 per move, so feasibility is many moves away.
     */
    private fun makeProblem(): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = "clockProbe",
            modelIdentifier = MODEL_ID,
            objFnResponseName = OBJ,
            inputNames = inputNames(),
            responseNames = listOf(LOAD)
        )
        for (name in inputNames()) {
            pd.inputVariable(name, 0.0, UPPER)
        }
        pd.responseConstraint(LOAD, rhsValue = LIMIT, inequalityType = InequalityType.LESS_THAN)
        return pd
    }

    private fun makeEvaluator(pd: ProblemDefinition): EvaluatorIfc {
        val oracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ, LOAD),
            ResponseFunctionBuilderIfc { streamProvider ->
                val stream = streamProvider.rnStream(1)
                ResponseFunctionIfc { inputs ->
                    // The objective is flat apart from a whisper of noise, so the only thing that
                    // can drive the search is the constraint violation.
                    mapOf(
                        OBJ to 1.0 + 1.0e-6 * stream.randU01(),
                        LOAD to inputs.values.sum()
                    )
                }
            }
        )
        return Evaluator(pd, oracle)
    }

    private fun startAtUpperBound(pd: ProblemDefinition) =
        pd.toInputMap(inputNames().associateWith { UPPER }.toMutableMap())

    private fun loadAt(solver: Solver): Double =
        solver.bestSolution.inputMap.values.sum()

    /** Evaluates one point through the evaluator and returns the resulting solution. */
    private fun solutionAt(
        pd: ProblemDefinition,
        evaluator: EvaluatorIfc,
        value: Double,
        replications: Int = 5
    ): ksl.simopt.evaluator.Solution {
        val inputs = pd.toInputMap(inputNames().associateWith { value }.toMutableMap())
        val request = ksl.simopt.evaluator.EvaluationRequest(
            modelIdentifier = pd.modelIdentifier,
            modelInputs = listOf(
                ksl.simopt.evaluator.ModelInputs(
                    modelIdentifier = pd.modelIdentifier,
                    numReplications = replications,
                    inputs = inputs,
                    responseNames = pd.allResponseNames.toSet()
                )
            )
        )
        return evaluator.evaluate(request).values.first()
    }

    @Test
    @DisplayName("A hill climber started infeasible works its way toward the feasible region")
    fun hillClimberEscapesAnInfeasibleStart() {
        val pd = makeProblem()
        val solver = StochasticHillClimber(
            pd, makeEvaluator(pd),
            maximumIterations = 100_000,
            replicationsPerEvaluation = 5
        )
        solver.startingPoint = startAtUpperBound(pd)
        solver.solutionQualityEvaluator = ReplicationBudgetStoppingCriterion(replicationBudget = 5_000)
        solver.runAllIterations()

        val load = loadAt(solver)
        assertTrue(load < DIMENSION * UPPER) {
            "The search never left its starting point: load $load is still the starting sum"
        }
        assertTrue(load <= LIMIT) {
            "The search did not reach the feasible region: load $load exceeds the limit of $LIMIT"
        }
    }

    @Test
    @DisplayName("Simulated annealing started infeasible works its way toward the feasible region")
    fun simulatedAnnealingEscapesAnInfeasibleStart() {
        // Annealing decides with its own Metropolis rule on the difference of penalized values,
        // so it needs the same common clock: a spurious positive difference does not merely fail
        // to improve, it makes acceptance exponentially unlikely.
        val pd = makeProblem()
        val solver = SimulatedAnnealing(
            pd, makeEvaluator(pd),
            maximumIterations = 100_000,
            replicationsPerEvaluation = FixedReplicationsPerEvaluation(5)
        )
        solver.startingPoint = startAtUpperBound(pd)
        solver.solutionQualityEvaluator = ReplicationBudgetStoppingCriterion(replicationBudget = 5_000)
        solver.runAllIterations()

        val load = loadAt(solver)
        assertTrue(load < DIMENSION * UPPER) {
            "The search never left its starting point: load $load is still the starting sum"
        }
        assertTrue(load <= LIMIT) {
            "The search did not reach the feasible region: load $load exceeds the limit of $LIMIT"
        }
    }

    @Test
    @DisplayName("A less violating solution wins however late it was evaluated")
    fun comparisonDoesNotFavorTheOlderSolution() {
        val pd = makeProblem()
        val evaluator = makeEvaluator(pd)
        val solver = StochasticHillClimber(pd, evaluator, replicationsPerEvaluation = 5)

        // two real solutions: one badly infeasible, one much closer to the limit
        val worse = solutionAt(pd, evaluator, UPPER)
        val better = solutionAt(pd, evaluator, 4.0)

        // the better solution evaluated much later than the worse one -- the situation a search
        // is in on every iteration after the first
        for (clock in listOf(1 to 2, 1 to 50, 1 to 5_000, 3 to 900)) {
            val old = worse.atEvaluation(clock.first)
            val new = better.atEvaluation(clock.second)
            assertTrue(solver.compare(new, old) < 0) {
                "A solution violating by ${'$'}{new.responseConstraintViolationPenalty} lost to one violating " +
                    "by ${'$'}{old.responseConstraintViolationPenalty} because it was evaluated later " +
                    "(clocks ${clock.second} against ${clock.first})"
            }
        }
    }

    @Test
    @DisplayName("Comparison is antisymmetric whatever the two clocks are")
    fun comparisonIsAntisymmetricAcrossClocks() {
        val pd = makeProblem()
        val evaluator = makeEvaluator(pd)
        val solver = StochasticHillClimber(pd, evaluator, replicationsPerEvaluation = 5)
        val a = solutionAt(pd, evaluator, UPPER).atEvaluation(2)
        val b = solutionAt(pd, evaluator, 4.0).atEvaluation(400)

        assertEquals(-solver.compare(a, b), solver.compare(b, a)) {
            "compare(a, b) and compare(b, a) disagree in magnitude or sign"
        }
    }

    @Test
    @DisplayName("With no constraints the clock cannot matter")
    fun unconstrainedComparisonIsUnaffectedByTheClock() {
        val pd = ProblemDefinition(
            problemName = "clockProbeUnconstrained",
            modelIdentifier = MODEL_ID,
            objFnResponseName = OBJ,
            inputNames = inputNames()
        )
        for (name in inputNames()) {
            pd.inputVariable(name, 0.0, UPPER)
        }
        val oracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ),
            ResponseFunctionBuilderIfc { _ ->
                ResponseFunctionIfc { inputs -> mapOf(OBJ to inputs.values.sum()) }
            }
        )
        val evaluator = Evaluator(pd, oracle)
        val solver = StochasticHillClimber(pd, evaluator, replicationsPerEvaluation = 5)
        val low = solutionAt(pd, evaluator, 1.0)
        val high = solutionAt(pd, evaluator, 9.0)

        // with no penalty term the ordering is the raw objective at any pair of clocks
        assertTrue(solver.compare(low.atEvaluation(1), high.atEvaluation(9_000)) < 0)
        assertTrue(solver.compare(low.atEvaluation(9_000), high.atEvaluation(1)) < 0)
    }
}
