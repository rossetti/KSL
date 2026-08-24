package ksl.simopt.solvers.concurrent

import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.evaluator.ResponseFunctionOracle
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.AppreciateDepreciateSequence
import ksl.simopt.problem.DynamicPolynomialPenalty
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ParkKimMemory
import ksl.simopt.problem.ParkKimPenalty
import ksl.simopt.problem.ProblemDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Confirmation selects the REPORTED answer, and must do so feasibility-first.
 *
 * Each member of a concurrent run hands confirmation its `Solver.bestSolution`, which is already
 * chosen with `FeasibilityFirstComparator`: confidently response-feasible ahead of not, raw
 * objective among feasibles, least violation among the rest. Confirmation re-simulates those
 * finalists under common random numbers to sharpen the estimates, and must then select by the same
 * rule. Selecting by the penalized objective instead — as it once did — discards the guarantee the
 * candidates arrived with, because the penalty multiplier is iteration-relative and can be far
 * smaller than the objective gap it is meant to outweigh.
 *
 * That is not a tuning question. It is why a benchmark could report a design violating four
 * constraints, one of them by 4.5 standard deviations, in preference to a feasible design its own
 * solvers had already found: the penalty standing against an 8,000-unit objective gap was 8.5.
 *
 * These tests pin the rule against the two things that previously leaked into it — the evaluation
 * clock and the penalty memory. Both still matter for what a run RECORDS, and both are held here to
 * be irrelevant to what it SELECTS.
 */
@Timeout(120)
class ConfirmationSelectionTest {

    private companion object {
        const val MODEL_ID = "confirmationSelectionProbe"
        const val OBJ = "objFn"
        const val USAGE = "usage"

        /**
         * Two designs on opposite sides of the constraint, priced so that the penalized rule and
         * the feasibility-first rule disagree at a weak penalty. With `P = 100 * k * v` and the
         * constraint `usage <= 1.0`:
         *
         *     cheap-infeasible: objective 100, usage 2.0  ->  violation 1.0  ->  100 + 100k
         *     costly-feasible:  objective 300, usage 0.0  ->  violation 0.0  ->  300
         *
         *     at k = 1:   200 vs 300  ->  the PENALIZED rule prefers the INFEASIBLE design
         *     at k = 500: 50100 vs 300 -> the penalized rule agrees with feasibility-first again
         *
         * So k = 1 is the discriminating case, and it is exactly the clock a freshly built
         * confirmation evaluator supplies.
         */
        const val CHEAP_INFEASIBLE_OBJECTIVE = 100.0
        const val CHEAP_INFEASIBLE_USAGE = 2.0
        const val COSTLY_FEASIBLE_OBJECTIVE = 300.0
        const val COSTLY_FEASIBLE_USAGE = 0.0
        const val CONSTRAINT_LIMIT = 1.0

        /** A second feasible design, cheaper than the first, for the tie-break test. */
        const val CHEAPER_FEASIBLE_OBJECTIVE = 250.0

        const val WEAK_CLOCK = 1
        const val STRONG_CLOCK = 500
        const val PFM_VISITS = 10
    }

    /**
     * A problem with no noise: the objective and the constrained response read straight off the
     * inputs, so re-simulation reproduces a design exactly and any change in the selected winner is
     * attributable to the selection rule alone.
     */
    private fun makeProblem(penalized: Boolean = false): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = "confirmationSelectionProbe",
            modelIdentifier = MODEL_ID,
            objFnResponseName = OBJ,
            inputNames = listOf("objective", "usage"),
            responseNames = listOf(USAGE)
        )
        pd.inputVariable("objective", 0.0, 1000.0)
        pd.inputVariable("usage", 0.0, 1000.0)
        pd.responseConstraint(
            USAGE, rhsValue = CONSTRAINT_LIMIT, inequalityType = InequalityType.LESS_THAN,
            penaltyFunction = if (penalized) {
                ParkKimPenalty(
                    sequence = AppreciateDepreciateSequence(appreciationFactor = 2.0, depreciationFactor = 0.5),
                    fallback = DynamicPolynomialPenalty()
                )
            } else null
        )
        return pd
    }

    private fun makeEvaluator(pd: ProblemDefinition): EvaluatorIfc {
        val oracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ, USAGE),
            ResponseFunctionBuilderIfc { _ ->
                ResponseFunctionIfc { inputs ->
                    mapOf(OBJ to inputs.getValue("objective"), USAGE to inputs.getValue("usage"))
                }
            }
        )
        return Evaluator(pd, oracle)
    }

    private fun solutionAt(
        pd: ProblemDefinition,
        evaluator: EvaluatorIfc,
        objective: Double,
        usage: Double,
        clock: Int
    ): Solution {
        val inputs = pd.toInputMap(mutableMapOf("objective" to objective, "usage" to usage))
        val request = EvaluationRequest(
            modelIdentifier = pd.modelIdentifier,
            modelInputs = listOf(ModelInputs(pd.modelIdentifier, 20, inputs, pd.allResponseNames.toSet()))
        )
        return evaluator.evaluate(request).values.first().atEvaluation(clock)
    }

    private fun confirm(pd: ProblemDefinition, candidates: List<Solution>): ConfirmationOutcome =
        SolutionConfirmation.confirmBest(
            candidates = candidates,
            evaluator = makeEvaluator(pd),
            problemDefinition = pd,
            options = ConfirmationOptions(topK = 3, replicationsPerCandidate = 20)
        )

    private fun winningObjective(outcome: ConfirmationOutcome): Double =
        outcome.winner.inputMap.getValue("objective")

    /**
     * The fixture: at the weak clock the penalized rule really does prefer the infeasible design.
     * Without this the headline test could pass for the wrong reason.
     */
    @Test
    @DisplayName("At a weak penalty the penalized rule prefers the cheaper infeasible design")
    fun fixtureDisagreesAtAWeakPenalty() {
        val pd = makeProblem()
        val evaluator = makeEvaluator(pd)
        val infeasible = solutionAt(pd, evaluator, CHEAP_INFEASIBLE_OBJECTIVE, CHEAP_INFEASIBLE_USAGE, WEAK_CLOCK)
        val feasible = solutionAt(pd, evaluator, COSTLY_FEASIBLE_OBJECTIVE, COSTLY_FEASIBLE_USAGE, WEAK_CLOCK)

        assertTrue(infeasible.penalizedObjFncValue < feasible.penalizedObjFncValue) {
            "the fixture no longer disagrees: infeasible=${infeasible.penalizedObjFncValue} " +
                "feasible=${feasible.penalizedObjFncValue}"
        }
    }

    /** The rule. This is the `CallCenterContest` failure in miniature. */
    @Test
    @DisplayName("A feasible candidate wins over a cheaper infeasible one at any penalty strength")
    fun feasibleBeatsCheaperInfeasibleAtAnyPenaltyStrength() {
        val pd = makeProblem()
        val evaluator = makeEvaluator(pd)
        for (clock in listOf(WEAK_CLOCK, 10, 100, STRONG_CLOCK)) {
            val candidates = listOf(
                solutionAt(pd, evaluator, CHEAP_INFEASIBLE_OBJECTIVE, CHEAP_INFEASIBLE_USAGE, clock),
                solutionAt(pd, evaluator, COSTLY_FEASIBLE_OBJECTIVE, COSTLY_FEASIBLE_USAGE, clock)
            )
            assertEquals(COSTLY_FEASIBLE_OBJECTIVE, winningObjective(confirm(pd, candidates))) {
                "at clock $clock confirmation returned the infeasible design; selection must not " +
                    "depend on whether the penalty happens to outweigh the objective gap"
            }
        }
    }

    /** Clock-independence stated directly: the same candidates, wildly different clocks, one winner. */
    @Test
    @DisplayName("The confirmed winner does not depend on the evaluation clock")
    fun winnerIsIndependentOfTheEvaluationClock() {
        val pd = makeProblem()
        val evaluator = makeEvaluator(pd)
        fun winnerAtClock(clock: Int): Double = winningObjective(
            confirm(
                pd, listOf(
                    solutionAt(pd, evaluator, CHEAP_INFEASIBLE_OBJECTIVE, CHEAP_INFEASIBLE_USAGE, clock),
                    solutionAt(pd, evaluator, COSTLY_FEASIBLE_OBJECTIVE, COSTLY_FEASIBLE_USAGE, clock)
                )
            )
        )
        assertEquals(winnerAtClock(WEAK_CLOCK), winnerAtClock(STRONG_CLOCK)) {
            "the winner changed with the clock, so selection is still reading the penalty"
        }
    }

    /** Among candidates that are all feasible, the smaller objective wins — feasibility-first's second key. */
    @Test
    @DisplayName("Among feasible candidates the smaller objective wins")
    fun smallerObjectiveWinsAmongFeasibleCandidates() {
        val pd = makeProblem()
        val evaluator = makeEvaluator(pd)
        val candidates = listOf(
            solutionAt(pd, evaluator, COSTLY_FEASIBLE_OBJECTIVE, COSTLY_FEASIBLE_USAGE, WEAK_CLOCK),
            solutionAt(pd, evaluator, CHEAPER_FEASIBLE_OBJECTIVE, COSTLY_FEASIBLE_USAGE, WEAK_CLOCK)
        )
        assertEquals(CHEAPER_FEASIBLE_OBJECTIVE, winningObjective(confirm(pd, candidates)))
    }

    /**
     * Penalty memory is search state that confirmation carries forward for the record; it must not
     * reach the selection. A candidate whose PFM memory says it is badly infeasible and one with no
     * memory at all select the same way, because feasibility is judged from the estimates.
     */
    @Test
    @DisplayName("The confirmed winner does not depend on penalty memory")
    fun winnerIsIndependentOfPenaltyMemory() {
        val pd = makeProblem(penalized = true)
        val evaluator = makeEvaluator(pd)
        val infeasible = solutionAt(pd, evaluator, CHEAP_INFEASIBLE_OBJECTIVE, CHEAP_INFEASIBLE_USAGE, WEAK_CLOCK)
        val feasible = solutionAt(pd, evaluator, COSTLY_FEASIBLE_OBJECTIVE, COSTLY_FEASIBLE_USAGE, WEAK_CLOCK)

        val withoutMemory = confirm(pd, listOf(infeasible, feasible))
        val withMemory = confirm(
            pd, listOf(
                infeasible.copy(penaltyMemory = mapOf(USAGE to ParkKimMemory(PFM_VISITS, 50.0, 1000.0))),
                feasible.copy(penaltyMemory = mapOf(USAGE to ParkKimMemory(PFM_VISITS, -50.0, 1000.0)))
            )
        )
        assertEquals(COSTLY_FEASIBLE_OBJECTIVE, winningObjective(withoutMemory))
        assertEquals(winningObjective(withoutMemory), winningObjective(withMemory)) {
            "penalty memory changed the selected winner; it is carried for the record only"
        }
    }

    /**
     * The mechanism the clock-independence above protects against: a newly built evaluator — which
     * is what the confirmation stage is given — stamps its first batch at 1, the weakest penalty a
     * dynamic penalty function ever applies.
     */
    @Test
    @DisplayName("A fresh evaluator stamps its first batch at clock 1")
    fun freshEvaluatorStampsAtClockOne() {
        val pd = makeProblem()
        val inputs = pd.toInputMap(mutableMapOf("objective" to 1.0, "usage" to 1.0))
        val request = EvaluationRequest(
            modelIdentifier = pd.modelIdentifier,
            modelInputs = listOf(ModelInputs(pd.modelIdentifier, 20, inputs, pd.allResponseNames.toSet()))
        )
        assertEquals(1, makeEvaluator(pd).evaluate(request).values.first().evaluationNumber)
    }
}
