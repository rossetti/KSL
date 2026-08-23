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
 * The confirmation stage must not reset the search's penalty state.
 *
 * A confirmed solution should keep the confirmation's ESTIMATES -- producing those under common
 * random numbers is the whole point of the stage -- and the SEARCH's penalty state. Penalty state
 * is search state; a selection stage must not manufacture its own. Two pieces of it are reset
 * today, and each on its own hands back the cheap infeasible design the search spent its entire
 * budget learning to reject.
 *
 * THE CLOCK. A dynamic penalty is deliberately weak early and strong late: `P = M0 * k * v` lets
 * a solver traverse infeasible ground at the start and makes it ruinous by the end. Confirmation
 * re-simulates on a DEDICATED evaluator, newly constructed, so its clock starts at zero and its
 * single call stamps every confirmed solution with evaluation number 1. Ranking them there
 * applies the weakest penalty of the run to the run's final decision.
 *
 * THE MEMORY. Park and Kim's PFM replaces the clock with an accumulated per-design-point memory,
 * and degrades to its memoryless fallback below two visits (see `ParkKimPenalty.penalty`).
 * Confirmation's request disables caching -- it must, because it uses CRN -- so the evaluator
 * folds memory from an empty prior and the confirmed solutions carry a visit count of one. PFM
 * therefore silently stops being PFM at exactly the moment it decides the answer: a run searches
 * under one penalty function and selects its winner under another, evaluated at clock 1.
 *
 * These are one defect with two mechanisms, and one fix: carry the candidate's clock and memory
 * onto its confirmed solution. The confirmation sample is deliberately correlated ACROSS designs
 * by CRN, so it is not folded in as a further PFM visit; the search's memory is carried forward
 * unchanged.
 *
 * This is the same failure family as the `Solver.compare` penalty clock, and it was missed when
 * that one was fixed: the solvers were audited and the selection stage that reports the answer
 * was not.
 */
@Timeout(120)
class ConfirmationPenaltyStateTest {

    private companion object {
        const val MODEL_ID = "confirmationClockProbe"
        const val OBJ = "objFn"
        const val USAGE = "usage"

        /**
         * The clock a search would have reached by the time it hands its best solution over.
         * Any value large enough to make the penalty bite will do; 50 is far below what a real
         * run accumulates.
         */
        const val SEARCH_CLOCK = 50

        /**
         * Two designs, chosen so the penalty decides between them and the clock decides the
         * penalty. With the default `P = 100 * k * v`:
         *
         *     cheap-infeasible: objective 100, violation 0.5  ->  100 + 50k
         *     costly-feasible:  objective 200, violation 0.0  ->  200
         *
         *     at k = 1  (a fresh evaluator's clock):  150 vs 200  ->  the INFEASIBLE design wins
         *     at k = 50 (the search's clock):        2600 vs 200  ->  the FEASIBLE design wins
         */
        const val CHEAP_INFEASIBLE_OBJECTIVE = 100.0
        const val CHEAP_INFEASIBLE_VIOLATION = 0.5
        const val COSTLY_FEASIBLE_OBJECTIVE = 200.0
        const val COSTLY_FEASIBLE_VIOLATION = 0.0

        /**
         * The PFM memories a search would have accumulated over ten visits to each design:
         *
         *     cheap-infeasible: S =  50 / 10 =  5, lambda = 1000  ->  penalty = lambda * [S]+ = 5000
         *     costly-feasible:  S = -50 / 10 = -5                 ->  [S]+ = 0, no penalty
         *
         *     with the search's memory:  100 + 5000 = 5100  vs  200  ->  the FEASIBLE design wins
         *     with memory discarded:     PFM degrades to the fallback at clock 1,
         *                                100 + 100 * 1 * 0.5 = 150  vs  200  ->  the INFEASIBLE one
         */
        const val PFM_VISITS = 10
        const val PFM_INFEASIBLE_CUMULATIVE_ZETA = 50.0
        const val PFM_FEASIBLE_CUMULATIVE_ZETA = -50.0
        const val PFM_LAMBDA = 1000.0
    }

    /**
     * A problem with no noise: both the objective and the constraint response read straight off
     * the inputs, so confirmation re-simulating a design reproduces it exactly and any change in
     * the ranking is attributable to the penalty clock alone.
     */
    private fun makeProblem(): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = "confirmationClockProbe",
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

    /** Evaluates one design point and stamps the result at the given clock. */
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

    /** The two candidates a pair of solver members would hand to confirmation. */
    private fun makeCandidates(pd: ProblemDefinition, evaluator: EvaluatorIfc): Pair<Solution, Solution> {
        val cheapInfeasible = solutionAt(
            pd, evaluator, CHEAP_INFEASIBLE_OBJECTIVE, CHEAP_INFEASIBLE_VIOLATION, SEARCH_CLOCK
        )
        val costlyFeasible = solutionAt(
            pd, evaluator, COSTLY_FEASIBLE_OBJECTIVE, COSTLY_FEASIBLE_VIOLATION, SEARCH_CLOCK
        )
        return cheapInfeasible to costlyFeasible
    }

    /**
     * The fixture itself: at the clock the search ended on, the feasible design is preferred.
     * If this fails the other assertions prove nothing.
     */
    @Test
    @DisplayName("At the search clock the feasible candidate is the better one")
    fun fixtureRanksFeasibleFirstAtTheSearchClock() {
        val pd = makeProblem()
        val evaluator = makeEvaluator(pd)
        val (cheapInfeasible, costlyFeasible) = makeCandidates(pd, evaluator)

        assertTrue(costlyFeasible.penalizedObjFncValue < cheapInfeasible.penalizedObjFncValue) {
            "at clock $SEARCH_CLOCK expected the feasible candidate to win, got " +
                "feasible=${costlyFeasible.penalizedObjFncValue} " +
                "infeasible=${cheapInfeasible.penalizedObjFncValue}"
        }
        // and the ordering is the other way round at a fresh evaluator's clock
        assertTrue(
            cheapInfeasible.atEvaluation(1).penalizedObjFncValue <
                costlyFeasible.atEvaluation(1).penalizedObjFncValue
        ) { "the fixture no longer flips between clock 1 and clock $SEARCH_CLOCK" }
    }

    /** The mechanism: a freshly constructed evaluator stamps its first results at clock 1. */
    @Test
    @DisplayName("A fresh evaluator stamps its first batch at clock 1")
    fun freshEvaluatorStampsAtClockOne() {
        val pd = makeProblem()
        val inputs = pd.toInputMap(mutableMapOf("objective" to 1.0, "violation" to 1.0))
        val request = EvaluationRequest(
            modelIdentifier = pd.modelIdentifier,
            modelInputs = listOf(ModelInputs(pd.modelIdentifier, 2, inputs, pd.allResponseNames.toSet()))
        )
        val solution = makeEvaluator(pd).evaluate(request).values.first()
        assertEquals(1, solution.evaluationNumber) {
            "a newly constructed evaluator stamps its first batch at 1, which is the weakest " +
                "penalty a dynamic penalty function ever applies"
        }
    }

    /**
     * The defect. Confirmation is given two candidates that a search ranked at its own clock,
     * re-simulates them on a dedicated evaluator, and returns the winner. Because that evaluator
     * is new, the ranking happens at clock 1 and the infeasible design is handed back.
     */
    @Test
    @DisplayName("confirmBest must not rank finalists at the confirmation evaluator's clock")
    fun confirmBestRanksAtTheSearchClock() {
        val pd = makeProblem()
        val searchEvaluator = makeEvaluator(pd)
        val (cheapInfeasible, costlyFeasible) = makeCandidates(pd, searchEvaluator)

        // the dedicated evaluator BenchmarkExperiment provisions for the confirmation stage
        val confirmationEvaluator = makeEvaluator(pd)
        val outcome = SolutionConfirmation.confirmBest(
            candidates = listOf(cheapInfeasible, costlyFeasible),
            evaluator = confirmationEvaluator,
            problemDefinition = pd,
            options = ConfirmationOptions(topK = 2, replicationsPerCandidate = 2)
        )

        val winningObjective = outcome.winner.inputMap.getValue("objective")
        assertEquals(COSTLY_FEASIBLE_OBJECTIVE, winningObjective) {
            "confirmation returned the infeasible design. Its finalists were stamped at " +
                "evaluation number ${outcome.winner.evaluationNumber}, so the penalty was " +
                "applied at that clock rather than at the search clock of $SEARCH_CLOCK"
        }
    }

    // ---------------------------------------------------------------------------------------
    // The memory mechanism: Park-Kim PFM
    // ---------------------------------------------------------------------------------------

    /**
     * The same two designs, judged by PFM instead of by the clock. The memories below are the
     * ones a search would have accumulated over ten visits to each point:
     *
     *     cheap-infeasible: S = 50 / 10 =  5, lambda = 1000  ->  penalty = lambda * [S]+ = 5000
     *     costly-feasible:  S = -50 / 10 = -5               ->  [S]+ = 0, no penalty
     *
     *     with the search's memory:  100 + 5000 = 5100  vs  200  ->  the FEASIBLE design wins
     *     with memory discarded:     PFM degrades to the fallback at clock 1,
     *                                100 + 100 * 1 * 0.5 = 150  vs  200  ->  the INFEASIBLE one
     */

    /** The probe problem with its response constraint penalized by PFM rather than by the clock. */
    private fun makePfmProblem(): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = "confirmationMemoryProbe",
            modelIdentifier = MODEL_ID,
            objFnResponseName = OBJ,
            inputNames = listOf("objective", "violation"),
            responseNames = listOf(USAGE)
        )
        pd.inputVariable("objective", 0.0, 1000.0)
        pd.inputVariable("violation", 0.0, 1000.0)
        pd.responseConstraint(
            USAGE, rhsValue = 0.0, inequalityType = InequalityType.LESS_THAN,
            penaltyFunction = ParkKimPenalty(
                sequence = AppreciateDepreciateSequence(appreciationFactor = 2.0, depreciationFactor = 0.5),
                fallback = DynamicPolynomialPenalty()
            )
        )
        return pd
    }

    /** A candidate carrying the PFM memory a search would have built up for its design point. */
    private fun withPfmMemory(solution: Solution, cumulativeZeta: Double): Solution =
        solution.copy(
            penaltyMemory = mapOf(USAGE to ParkKimMemory(PFM_VISITS, cumulativeZeta, PFM_LAMBDA))
        )

    private fun makePfmCandidates(pd: ProblemDefinition, evaluator: EvaluatorIfc): Pair<Solution, Solution> {
        val cheapInfeasible = withPfmMemory(
            solutionAt(pd, evaluator, CHEAP_INFEASIBLE_OBJECTIVE, CHEAP_INFEASIBLE_VIOLATION, SEARCH_CLOCK),
            PFM_INFEASIBLE_CUMULATIVE_ZETA
        )
        val costlyFeasible = withPfmMemory(
            solutionAt(pd, evaluator, COSTLY_FEASIBLE_OBJECTIVE, COSTLY_FEASIBLE_VIOLATION, SEARCH_CLOCK),
            PFM_FEASIBLE_CUMULATIVE_ZETA
        )
        return cheapInfeasible to costlyFeasible
    }

    /** The PFM fixture: with the search's memory in place, the feasible design is preferred. */
    @Test
    @DisplayName("With the search's PFM memory the feasible candidate is the better one")
    fun pfmFixtureRanksFeasibleFirstWithSearchMemory() {
        val pd = makePfmProblem()
        val evaluator = makeEvaluator(pd)
        val (cheapInfeasible, costlyFeasible) = makePfmCandidates(pd, evaluator)

        assertTrue(costlyFeasible.penalizedObjFncValue < cheapInfeasible.penalizedObjFncValue) {
            "with the search's memory expected the feasible candidate to win, got " +
                "feasible=${costlyFeasible.penalizedObjFncValue} " +
                "infeasible=${cheapInfeasible.penalizedObjFncValue}"
        }
        // and it flips once the memory is gone, which is what confirmation does to it
        val strippedInfeasible = cheapInfeasible.copy(penaltyMemory = emptyMap()).atEvaluation(1)
        val strippedFeasible = costlyFeasible.copy(penaltyMemory = emptyMap()).atEvaluation(1)
        assertTrue(strippedInfeasible.penalizedObjFncValue < strippedFeasible.penalizedObjFncValue) {
            "the PFM fixture no longer flips when the memory is discarded"
        }
    }

    /**
     * The mechanism: an evaluator with no cache folds PFM memory from an empty prior, so a design
     * point it has never seen comes back with a single visit -- below the two PFM needs to engage.
     */
    @Test
    @DisplayName("A fresh evaluator returns PFM memory of a single visit")
    fun freshEvaluatorReturnsSingleVisitMemory() {
        val pd = makePfmProblem()
        val inputs = pd.toInputMap(mutableMapOf("objective" to 1.0, "violation" to 1.0))
        val request = EvaluationRequest(
            modelIdentifier = pd.modelIdentifier,
            modelInputs = listOf(ModelInputs(pd.modelIdentifier, 2, inputs, pd.allResponseNames.toSet()))
        )
        val solution = makeEvaluator(pd).evaluate(request).values.first()
        val memory = solution.penaltyMemory[USAGE] as? ParkKimMemory
        assertEquals(1, memory?.visitCount) {
            "a cacheless evaluator folds from an empty prior, giving one visit, which is below " +
                "the two ParkKimPenalty.penalty requires before it uses the memory at all"
        }
    }

    /**
     * The defect, memory half. Confirmation discards the accumulated PFM memory, so the finalists
     * are ranked by the memoryless fallback -- a different penalty function from the one the
     * search ran under -- and the infeasible design is handed back.
     */
    @Test
    @DisplayName("confirmBest must rank finalists with the search's PFM memory")
    fun confirmBestRanksWithTheSearchPenaltyMemory() {
        val pd = makePfmProblem()
        val searchEvaluator = makeEvaluator(pd)
        val (cheapInfeasible, costlyFeasible) = makePfmCandidates(pd, searchEvaluator)

        val confirmationEvaluator = makeEvaluator(pd)
        val outcome = SolutionConfirmation.confirmBest(
            candidates = listOf(cheapInfeasible, costlyFeasible),
            evaluator = confirmationEvaluator,
            problemDefinition = pd,
            options = ConfirmationOptions(topK = 2, replicationsPerCandidate = 2)
        )

        val winningObjective = outcome.winner.inputMap.getValue("objective")
        val winnerVisits = (outcome.winner.penaltyMemory[USAGE] as? ParkKimMemory)?.visitCount
        assertEquals(COSTLY_FEASIBLE_OBJECTIVE, winningObjective) {
            "confirmation returned the infeasible design. Its winner carries a PFM visit count " +
                "of $winnerVisits rather than the search's $PFM_VISITS, so PFM degraded to its " +
                "memoryless fallback and the ranking was made by a different penalty function " +
                "than the search used"
        }
    }
}
