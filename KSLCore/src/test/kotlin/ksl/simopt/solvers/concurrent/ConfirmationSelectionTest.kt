package ksl.simopt.solvers.concurrent

import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.evaluator.ResponseFunctionOracle
import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.AppreciateDepreciateSequence
import ksl.simopt.problem.DynamicPolynomialPenalty
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ParkKimMemory
import ksl.simopt.problem.ParkKimPenalty
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.problem.ResponseConstraint
import ksl.utilities.random.rvariable.NormalRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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

        const val SCREEN_MODEL = "screeningProbe"
        const val COST = "cost"
        const val UTIL = "util"
        const val UTIL_LIMIT = 0.5

        /** Cheap and genuinely feasible: true utilization 0.30 against a limit of 0.50. */
        const val CHEAP_COST = 100.0
        const val CHEAP_UTIL = 0.30

        /** Expensive and even more feasible. A study should not prefer it: it costs twice as much. */
        const val COSTLY_COST = 200.0
        const val COSTLY_UTIL = 0.10

        /** The search-time precision. Both designs are feasible; at this precision one cannot be shown to be. */
        const val SEARCH_REPLICATIONS = 10
    
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

    // ── Selection degeneracy (C1) ─────────────────────────────────────────────────────────
    //
    // Feasibility-first has a failure mode that looks like success. When NO candidate can be
    // declared confidently feasible, step 2 of the comparator cannot discriminate, every candidate
    // falls through to step 4, and the winner is ranked by constraint violation ALONE -- the
    // objective plays no part at all. Nothing in the output says so. That is what produced a
    // FacilitySizing winner verifying at P(stockout) = 0.122 against a 0.05 limit, reported with
    // the same confidence as any other result.
    //
    // These tests pin the flag that makes the condition visible, and pin what the selection
    // actually does while it holds.

    /**
     * The headline: when every candidate violates, the LEAST-VIOLATING one wins even though it is
     * by far the most expensive. The objective spread here is 9:1 and it changes nothing, which is
     * the point — a reader of the winner alone cannot tell that the objective was never consulted.
     */
    @Test
    @DisplayName("With no confidently feasible candidate the least-violating design wins and the objective is unused")
    fun degenerateSelectionRanksByViolationAloneAndIsFlagged() {
        val pd = makeProblem()
        val evaluator = makeEvaluator(pd)
        // Both violate `usage <= 1.0`. The cheap one violates by 2.0, the costly one by 0.5.
        val cheapBadlyInfeasible = solutionAt(pd, evaluator, 100.0, 3.0, WEAK_CLOCK)
        val costlyBarelyInfeasible = solutionAt(pd, evaluator, 900.0, 1.5, WEAK_CLOCK)

        val outcome = confirm(pd, listOf(cheapBadlyInfeasible, costlyBarelyInfeasible))

        assertTrue(outcome.selectionDegenerate) {
            "no candidate is confidently feasible, so the selection is degenerate and must say so"
        }
        assertEquals(0, outcome.numConfidentlyFeasible)
        assertEquals(2, outcome.numCandidates)
        assertEquals(900.0, winningObjective(outcome)) {
            "the winner must be the least-violating candidate; picking the cheaper one would mean " +
                "the objective still reached the decision"
        }
        assertEquals(1.5, outcome.winner.inputMap.getValue("usage"))
    }

    /**
     * The control. The same machinery on the same problem, with one feasible candidate present,
     * must NOT raise the flag — otherwise it would fire on every constrained problem and carry no
     * information.
     */
    @Test
    @DisplayName("One confidently feasible candidate is enough to make the selection non-degenerate")
    fun oneFeasibleCandidateClearsTheDegeneracyFlag() {
        val pd = makeProblem()
        val evaluator = makeEvaluator(pd)
        val candidates = listOf(
            solutionAt(pd, evaluator, CHEAP_INFEASIBLE_OBJECTIVE, CHEAP_INFEASIBLE_USAGE, WEAK_CLOCK),
            solutionAt(pd, evaluator, COSTLY_FEASIBLE_OBJECTIVE, COSTLY_FEASIBLE_USAGE, WEAK_CLOCK)
        )
        val outcome = confirm(pd, candidates)

        assertTrue(!outcome.selectionDegenerate)
        assertEquals(1, outcome.numConfidentlyFeasible)
        assertEquals(2, outcome.numCandidates)
        assertEquals(COSTLY_FEASIBLE_OBJECTIVE, winningObjective(outcome))
    }

    /**
     * A problem with no response constraints has nothing to be confidently feasible ABOUT: every
     * candidate passes trivially. The flag must stay down, or every unconstrained benchmark problem
     * would be reported as a degenerate selection.
     */
    @Test
    @DisplayName("An unconstrained problem is never reported as a degenerate selection")
    fun unconstrainedProblemIsNeverDegenerate() {
        val pd = ProblemDefinition(
            problemName = "unconstrainedProbe",
            modelIdentifier = MODEL_ID,
            objFnResponseName = OBJ,
            inputNames = listOf("objective", "usage"),
            responseNames = listOf(USAGE)
        )
        pd.inputVariable("objective", 0.0, 1000.0)
        pd.inputVariable("usage", 0.0, 1000.0)
        val evaluator = makeEvaluator(pd)
        val candidates = listOf(
            solutionAt(pd, evaluator, CHEAP_INFEASIBLE_OBJECTIVE, CHEAP_INFEASIBLE_USAGE, WEAK_CLOCK),
            solutionAt(pd, evaluator, COSTLY_FEASIBLE_OBJECTIVE, COSTLY_FEASIBLE_USAGE, WEAK_CLOCK)
        )
        val outcome = confirm(pd, candidates)

        assertTrue(!outcome.selectionDegenerate)
        assertEquals(2, outcome.numConfidentlyFeasible)
        // With no constraint to fail, the smaller objective wins on the comparator's third key.
        assertEquals(CHEAP_INFEASIBLE_OBJECTIVE, winningObjective(outcome))
    }

    /**
     * Confirmation is skipped when the finalists collapse to one distinct point, and that early
     * return must still carry the counts. This is the case a `tblConfirmation`-only record would
     * lose entirely, since it produces no candidate rows.
     */
    @Test
    @DisplayName("A skipped confirmation still reports the degeneracy counts")
    fun skippedConfirmationStillReportsCounts() {
        val pd = makeProblem()
        val evaluator = makeEvaluator(pd)
        val single = solutionAt(pd, evaluator, 100.0, 3.0, WEAK_CLOCK)
        val outcome = confirm(pd, listOf(single, single))

        assertTrue(outcome.confirmedSolutions.isEmpty()) { "the fixture must exercise the skip path" }
        assertEquals(0, outcome.numOracleCalls)
        assertEquals(2, outcome.numCandidates)
        assertEquals(0, outcome.numConfidentlyFeasible)
        assertTrue(outcome.selectionDegenerate)
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

    // ── C2/C3: injectable selection rule and two-stage screening ──────────────
    //
    // Feasibility-first ranks by a STATISTICAL test, so its answer depends on the precision of the
    // estimates it is handed -- and the estimates reaching confirmation come from the search, at the
    // weakest precision anywhere in the pipeline. The gate deciding which candidates earn expensive
    // confirmation runs therefore runs where it can discriminate least.
    //
    // How little, measured against ResponseConstraint.testFeasibility itself, is pinned by
    // `feasibilityTestCannotDiscriminateAtSearchPrecision` below.


    /** A noisy utilization problem: the constrained response carries substantial replication noise. */
    private fun screeningProblem(): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = SCREEN_MODEL,
            modelIdentifier = SCREEN_MODEL,
            objFnResponseName = COST,
            inputNames = listOf(COST, UTIL),
            responseNames = listOf(UTIL)
        )
        pd.inputVariable(COST, 0.0, 1000.0)
        pd.inputVariable(UTIL, 0.0, 1.0)
        pd.responseConstraint(UTIL, rhsValue = UTIL_LIMIT, inequalityType = InequalityType.LESS_THAN)
        return pd
    }

    private fun screeningEvaluator(pd: ProblemDefinition): EvaluatorIfc {
        val oracle = ResponseFunctionOracle(
            SCREEN_MODEL, setOf(COST, UTIL),
            ResponseFunctionBuilderIfc { streamProvider ->
                val noise = NormalRV(0.0, 0.25, 1, streamProvider)
                ResponseFunctionIfc { inputs ->
                    mapOf(
                        COST to inputs.getValue(COST),
                        UTIL to inputs.getValue(UTIL) + noise.value
                    )
                }
            }
        )
        return Evaluator(pd, oracle)
    }

    private fun screeningCandidate(
        pd: ProblemDefinition,
        evaluator: EvaluatorIfc,
        cost: Double,
        util: Double,
        replications: Int
    ): Solution {
        val inputs = pd.toInputMap(mutableMapOf(COST to cost, UTIL to util))
        val request = EvaluationRequest(
            modelIdentifier = SCREEN_MODEL,
            modelInputs = listOf(ModelInputs(SCREEN_MODEL, replications, inputs, pd.allResponseNames.toSet()))
        )
        return evaluator.evaluate(request).values.first()
    }

    /**
     * The arithmetic the screening stage exists because of, checked against the library's own
     * feasibility test rather than restated from a paper.
     *
     * On a `P(event) <= 0.05` chance constraint at 99% overall confidence, a candidate is declared
     * confidently feasible only when the one-sided upper limit falls below zero. At 30 replications
     * — the precision this study's searches ran at — that requires ZERO observed violations in all
     * 30. A candidate observing one violation sits at 0.033, comfortably inside the limit, and is
     * still rejected.
     *
     * So a degenerate selection on such a constraint is close to arithmetic rather than bad luck,
     * and it cannot be cured by a screening stage that is not sized against the constraint: at 100
     * replications only 0.01 passes. This test is what makes that claim checkable, and what would
     * fail if the feasibility test changed underneath the rationale.
     */
    @Test
    @DisplayName("The feasibility test cannot discriminate at search-time precision")
    fun feasibilityTestCannotDiscriminateAtSearchPrecision() {
        val constraint = ResponseConstraint(
            "pEvent", rhsValue = 0.05, inequalityType = InequalityType.LESS_THAN
        )
        val level = 0.99

        // k violations out of n, as a Bernoulli sample.
        fun estimateOf(k: Int, n: Int): EstimatedResponse {
            val pHat = k.toDouble() / n
            val variance = (k * (1 - pHat) * (1 - pHat) + (n - k) * pHat * pHat) / (n - 1)
            return EstimatedResponse("pEvent", pHat, variance, n.toDouble())
        }

        // At the search precision, a perfect record is the only thing that passes...
        assertTrue(constraint.testFeasibility(estimateOf(0, 30), level))
        assertTrue(!constraint.testFeasibility(estimateOf(1, 30), level)) {
            "1 violation in 30 is p = 0.033, inside the 0.05 limit, and is still rejected: that " +
                "rejection is the whole reason the screening stage exists"
        }

        // ...screening at 100 replications is still too coarse for a candidate at a realistic 0.02...
        assertTrue(constraint.testFeasibility(estimateOf(1, 100), level))
        assertTrue(!constraint.testFeasibility(estimateOf(2, 100), level)) {
            "screening at 100 replications would still reject a candidate at p = 0.02, so it " +
                "would inherit the degeneracy it was added to cure"
        }

        // ...and around 200 is where discrimination arrives.
        assertTrue(constraint.testFeasibility(estimateOf(4, 200), level)) {
            "200 replications should admit a candidate at p = 0.02"
        }
    }

    /**
     * The headline for C3. At the search precision one design is confidently feasible and the other
     * is not — on the noise, not on the truth, since both are genuinely feasible — so
     * feasibility-first puts the EXPENSIVE design first and confirmation reports it. Screening
     * re-evaluates both at a precision where the test can discriminate, both are then shown
     * feasible, the objective finally reaches the decision, and the cheaper design wins.
     *
     * Same search, same candidates, same comparator; twice the answer's quality for a screening
     * stage costing a fraction of the confirmation it gates.
     */
    @Test
    @DisplayName("Screening changes the finalist set and confirms the cheaper feasible design")
    fun screeningSelectsABetterWinnerThanSearchTimePrecisionDoes() {
        val pd = screeningProblem()
        val evaluator = screeningEvaluator(pd)
        val cheap = screeningCandidate(pd, evaluator, CHEAP_COST, CHEAP_UTIL, SEARCH_REPLICATIONS)
        val costly = screeningCandidate(pd, evaluator, COSTLY_COST, COSTLY_UTIL, SEARCH_REPLICATIONS)
        val candidates = listOf(cheap, costly)

        // The fixture: at search precision the test splits the two designs, though both are feasible.
        assertTrue(!cheap.isResponseConstraintFeasible()) { "the fixture no longer misjudges the cheap design" }
        assertTrue(costly.isResponseConstraintFeasible()) { "the fixture no longer favours the costly design" }

        val singleStage = SolutionConfirmation.confirmBest(
            candidates, screeningEvaluator(pd), pd,
            ConfirmationOptions(topK = 1, replicationsPerCandidate = 30)
        )
        assertEquals(COSTLY_COST, singleStage.winner.inputMap.getValue(COST)) {
            "without screening the search-time misjudgement carries straight through to the winner"
        }
        assertNull(singleStage.numConfidentlyFeasibleAfterScreening) {
            "no screening ran, so there is no post-screening reading to report"
        }

        val twoStage = SolutionConfirmation.confirmBest(
            candidates, screeningEvaluator(pd), pd,
            ConfirmationOptions(
                topK = 1, replicationsPerCandidate = 30,
                screenK = 2, screeningReplications = 200
            )
        )
        assertEquals(CHEAP_COST, twoStage.winner.inputMap.getValue(COST)) {
            "screening should have shown both designs feasible and let the objective decide"
        }
        assertEquals(2, twoStage.numConfidentlyFeasibleAfterScreening) {
            "both designs are genuinely feasible and screening should have established it"
        }
        assertTrue(twoStage.numReplicationsRequested > singleStage.numReplicationsRequested) {
            "screening consumes budget and must be accounted for, not spent silently"
        }
    }

    /** Screening is off by default, so an existing study's selection behaviour is unchanged. */
    @Test
    @DisplayName("Screening is disabled by default")
    fun screeningIsOffByDefault() {
        val options = ConfirmationOptions()
        assertTrue(!options.isScreeningEnabled)
        assertNull(options.screenK)
        assertNull(options.screeningReplications)
    }

    /**
     * Half-configured screening would silently not screen — the exact failure mode the stage was
     * added to remove. Both misconfigurations are refused at construction instead.
     */
    @Test
    @DisplayName("Misconfigured screening is refused rather than silently skipped")
    fun misconfiguredScreeningIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            ConfirmationOptions(screenK = 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConfirmationOptions(screeningReplications = 100)
        }
        // A pool smaller than the finalist count cannot change which candidates are confirmed.
        assertThrows(IllegalArgumentException::class.java) {
            ConfirmationOptions(topK = 5, screenK = 3, screeningReplications = 100)
        }
        // One replication carries no sample variance, so nothing could ever be declared feasible.
        assertThrows(IllegalArgumentException::class.java) {
            ConfirmationOptions(screenK = 10, screeningReplications = 1)
        }
    }

    /**
     * C2. Selection policy becomes a study-level variable: the same candidates under a different
     * rule give a different winner, with no library change. A benchmark can therefore run both and
     * report the difference rather than asserting which rule is right.
     */
    @Test
    @DisplayName("An injected comparator replaces the selection rule")
    fun injectedComparatorReplacesTheSelectionRule() {
        val pd = makeProblem()
        val evaluator = makeEvaluator(pd)
        val candidates = listOf(
            solutionAt(pd, evaluator, CHEAP_INFEASIBLE_OBJECTIVE, CHEAP_INFEASIBLE_USAGE, WEAK_CLOCK),
            solutionAt(pd, evaluator, COSTLY_FEASIBLE_OBJECTIVE, COSTLY_FEASIBLE_USAGE, WEAK_CLOCK)
        )

        // The default rule prefers the feasible design however much cheaper the infeasible one is.
        assertEquals(COSTLY_FEASIBLE_OBJECTIVE, winningObjective(confirm(pd, candidates)))

        // A rule that ignores feasibility entirely and takes the smallest objective picks the other.
        val objectiveOnly = Comparator<Solution> { a, b ->
            a.estimatedObjFncValue.compareTo(b.estimatedObjFncValue)
        }
        val outcome = SolutionConfirmation.confirmBest(
            candidates, makeEvaluator(pd), pd,
            ConfirmationOptions(
                topK = 3, replicationsPerCandidate = 20,
                selectionComparator = objectiveOnly
            )
        )
        assertEquals(CHEAP_INFEASIBLE_OBJECTIVE, outcome.winner.inputMap.getValue("objective")) {
            "the injected rule was not used; selection policy is not actually a variable"
        }

        // The feasibility COUNTS are a property of the solutions, not of the rule, so they are
        // reported the same way whichever comparator is supplied.
        assertEquals(1, outcome.numConfidentlyFeasible)
        assertTrue(!outcome.selectionDegenerate)
    }
}
