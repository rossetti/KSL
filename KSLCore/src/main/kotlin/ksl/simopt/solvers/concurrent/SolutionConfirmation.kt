package ksl.simopt.solvers.concurrent

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.FeasibilityFirstComparator
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.PenaltyMemory
import ksl.simopt.problem.ProblemDefinition

/**
 * The outcome of a confirmation stage.
 *
 * @param winner the winning solution after confirmation
 * @param confirmedSolutions the freshly confirmed solutions (empty when confirmation was
 * skipped because there was effectively a single candidate)
 * @param numOracleCalls the number of oracle design points the confirmation consumed
 * @param numReplicationsRequested the number of replications the confirmation consumed
 * @param numCandidates the number of candidates that entered ranking
 * @param numConfidentlyFeasible how many of those candidates passed
 * `Solution.isResponseConstraintFeasible` at the options' CI level, measured at the
 * candidates' own search-time precision — which is the precision the ranking actually used
 * @param numConfidentlyFeasibleAfterScreening how many candidates were confidently feasible once
 * re-evaluated by the screening stage, or null when screening did not run. This is the number that
 * says whether screening achieved what it exists for: a degenerate selection at search-time
 * precision followed by a positive count here is a selection that screening rescued, and a zero
 * here means the screening replications were too few for the constraint and the finalists were
 * still chosen by violation alone.
 * @param selectionDegenerate true when no candidate could be declared confidently feasible.
 * The comparator then cannot discriminate on feasibility, every candidate falls through to
 * its violation-penalty tie-break, and **the objective plays no part in choosing the
 * winner**: the reported winner is the most conservative candidate rather than the best
 * objective/feasibility trade-off. Always false for a problem with no response constraints,
 * where every candidate is trivially feasible. The condition is otherwise invisible in the
 * output, which is why it is recorded rather than merely logged.
 */
data class ConfirmationOutcome(
    val winner: Solution,
    val confirmedSolutions: List<Solution>,
    val numOracleCalls: Int,
    val numReplicationsRequested: Int,
    val numCandidates: Int,
    val numConfidentlyFeasible: Int,
    val selectionDegenerate: Boolean,
    val numConfidentlyFeasibleAfterScreening: Int? = null
)

/**
 * The confirmation stage for concurrent solver runs: re-evaluates the top candidate
 * solutions under common random numbers and picks the winner from the confirmed
 * estimates. Members of a concurrent run can return bests with different statistical
 * precision, so a winner picked from raw point estimates favors noise; a short CRN
 * comparison of the finalists is the standard ranking-and-selection remedy.
 */
object SolutionConfirmation {

    val logger: KLogger = KotlinLogging.logger {}

    /**
     * Ranks the candidates feasibility-first, takes the top candidates per the options,
     * re-evaluates their distinct input points in one CRN request (no caching), and returns the
     * winner by the same rule applied to the confirmed estimates.
     *
     * Invalid candidates (failed members) are excluded from ranking when at least one
     * valid candidate exists. When the finalists collapse to a single distinct input
     * point, no evaluation is performed and the best candidate is returned as-is.
     *
     * Selection uses [ksl.simopt.evaluator.FeasibilityFirstComparator], the same rule
     * `Solver.bestSolution` uses to choose what a solver recommends, so the benchmark and the
     * solvers agree about what "best" means. It is clock-independent, which is what cross-member,
     * cross-iteration selection requires; the penalized objective is a within-iteration search key
     * and is deliberately not used here.
     *
     * The confirmed solutions still carry the search's penalty state rather than the confirmation
     * evaluator's, so any penalized value a caller records remains meaningful.
     *
     * @param candidates the candidate solutions (typically the member bests), not empty
     * @param evaluator the evaluator used for the confirmation request
     * @param problemDefinition the problem the candidates belong to
     * @param options the confirmation configuration
     */
    fun confirmBest(
        candidates: List<Solution>,
        evaluator: EvaluatorIfc,
        problemDefinition: ProblemDefinition,
        options: ConfirmationOptions
    ): ConfirmationOutcome {
        require(candidates.isNotEmpty()) { "At least one candidate solution is required" }
        val usable = candidates.filter { it.isValid }.ifEmpty { candidates }
        // Confirmation selects the REPORTED answer across members and across iterations, which is
        // what FeasibilityFirstComparator exists for: it prefers a solution we are confident is
        // response-feasible, ranks feasibles by their raw objective, and never reads the penalized
        // objective -- whose multiplier is iteration-relative and therefore not comparable between
        // solutions found at different times. The candidates arriving here are already each
        // member's feasibility-first `Solver.bestSolution`; ranking them by penalized objective
        // discarded that guarantee and let a cheap infeasible candidate beat a feasible one
        // whenever the penalty was smaller than the objective gap.
        val recommendationComparator = options.selectionComparator
            ?: FeasibilityFirstComparator(options.recommendationCILevel)
        val selectionClock = candidates.maxOf { it.evaluationNumber }
        // Measured before ranking, on the same candidates and at the same CI level the comparator
        // uses, so this records what the comparator had to work with rather than a re-derivation.
        val numConfidentlyFeasible = usable.count {
            it.isResponseConstraintFeasible(options.recommendationCILevel)
        }
        val selectionDegenerate = numConfidentlyFeasible == 0
        if (selectionDegenerate) {
            logger.warn {
                "Confirmation selection is degenerate: none of ${usable.size} candidates is " +
                    "confidently response-feasible at level ${options.recommendationCILevel}, so " +
                    "the winner is ranked by constraint violation alone and the objective is unused."
            }
        }
        val ranked = usable.sortedWith(recommendationComparator)
        // Penalty state is search state. It is carried across unchanged rather than re-derived,
        // because a selection stage that manufactured its own would restart the clock at 1 and a
        // memoryful penalty would silently degrade to its memoryless fallback. Built from every
        // candidate, before any stage narrows the field, so both screening and confirmation restamp
        // from the same source.
        val priorMemory = LinkedHashMap<InputMap, Map<String, PenaltyMemory>>()
        for (candidate in ranked) {
            // first-wins, matching the rule distinctInputs uses to collapse duplicate points
            priorMemory.putIfAbsent(candidate.inputMap, candidate.penaltyMemory)
        }

        val screening = screenCandidates(
            ranked, evaluator, problemDefinition, options, recommendationComparator,
            selectionClock, priorMemory
        )
        val finalists = screening.finalists
        val distinctInputs = finalists.map { it.inputMap }.distinct()
        if (distinctInputs.size <= 1) {
            logger.debug { "Confirmation skipped: a single distinct finalist input point" }
            return ConfirmationOutcome(
                winner = finalists.first(),
                confirmedSolutions = emptyList(),
                // Confirmation itself spent nothing, but screening may already have: reporting zero
                // here would hide real budget consumption behind a skipped stage.
                numOracleCalls = screening.numOracleCalls,
                numReplicationsRequested = screening.numReplicationsRequested,
                numCandidates = usable.size,
                numConfidentlyFeasible = numConfidentlyFeasible,
                selectionDegenerate = selectionDegenerate,
                numConfidentlyFeasibleAfterScreening = screening.numConfidentlyFeasible
            )
        }
        val modelInputs = distinctInputs.map { inputMap ->
            ModelInputs(
                modelIdentifier = problemDefinition.modelIdentifier,
                numReplications = options.replicationsPerCandidate,
                inputs = inputMap,
                responseNames = problemDefinition.allResponseNames.toSet()
            )
        }
        // CRN across the finalists for a paired comparison; caching must be off under CRN.
        val request = EvaluationRequest(
            modelIdentifier = problemDefinition.modelIdentifier,
            modelInputs = modelInputs,
            crnOption = true,
            cachingAllowed = false
        )
        val confirmed = evaluator.evaluate(request).values.toList()
        // A confirmed solution keeps the confirmation's ESTIMATES -- producing those under CRN is
        // what this stage exists for -- and the SEARCH's penalty state. Penalty state is search
        // state, and a selection stage must not manufacture its own: the clock would restart at 1,
        // and a memoryful penalty (Park-Kim PFM) would arrive with a single visit and silently
        // degrade to its memoryless fallback, so the winner would be chosen by a different penalty
        // function than the search ran under.
        //
        // The memory is carried across unchanged rather than folded as a further visit. The
        // confirmation sample is deliberately CRN-correlated ACROSS designs, and PFM's
        // standardized measure assumes independent visits, so folding it in would claim more of
        // that sample than it can support. The map was built above, over every candidate.
        val restamped = confirmed.map { solution ->
            solution.copy(
                evaluationNumber = selectionClock,
                penaltyMemory = priorMemory[solution.inputMap] ?: solution.penaltyMemory
            )
        }
        val winner = restamped.minWithOrNull(recommendationComparator)
            ?: finalists.first()
        logger.debug { "Confirmation of ${distinctInputs.size} finalists complete" }
        return ConfirmationOutcome(
            winner = winner,
            confirmedSolutions = restamped,
            // Screening consumes real budget, so its cost is reported with confirmation's own
            // rather than left out of the accounting.
            numOracleCalls = modelInputs.size + screening.numOracleCalls,
            numReplicationsRequested = modelInputs.sumOf { it.numReplications } +
                screening.numReplicationsRequested,
            numCandidates = usable.size,
            numConfidentlyFeasible = numConfidentlyFeasible,
            selectionDegenerate = selectionDegenerate,
            numConfidentlyFeasibleAfterScreening = screening.numConfidentlyFeasible
        )
    }

    /**
     * What the screening stage produced: the finalists confirmation should run on, what screening
     * cost, and how many of the screened candidates could be declared feasible at the sharper
     * precision.
     */
    private data class ScreeningResult(
        val finalists: List<Solution>,
        val numOracleCalls: Int,
        val numReplicationsRequested: Int,
        val numConfidentlyFeasible: Int?
    )

    /**
     * Chooses the finalists, optionally via a screening stage.
     *
     * Without screening this is the single-stage behaviour: take the top `topK` of the ranking as it
     * stands, at each candidate's own search-time precision.
     *
     * With screening it takes `screenK` candidates, re-evaluates their distinct points together
     * under common random numbers at `screeningReplications`, and re-ranks THOSE re-evaluated
     * solutions before taking `topK`. The difference matters because the feasibility test the
     * ranking depends on cannot discriminate at search-time precision: on a `P(event) <= 0.05`
     * constraint at 30 replications, only a candidate observing zero violations in all 30 can be
     * declared feasible, so every realistic candidate falls to the violation tie-break and the
     * objective never reaches the decision. Screening moves that judgement to a precision where it
     * can be made — provided the replication count is actually sized for the constraint; see
     * `ConfirmationOptions.screeningReplications`.
     */
    private fun screenCandidates(
        ranked: List<Solution>,
        evaluator: EvaluatorIfc,
        problemDefinition: ProblemDefinition,
        options: ConfirmationOptions,
        comparator: Comparator<Solution>,
        selectionClock: Int,
        priorMemory: Map<InputMap, Map<String, PenaltyMemory>>
    ): ScreeningResult {
        val screenK = options.screenK
        val screeningReplications = options.screeningReplications
        if (screenK == null || screeningReplications == null) {
            return ScreeningResult(ranked.take(options.topK), 0, 0, null)
        }
        val pool = ranked.take(screenK)
        val distinctInputs = pool.map { it.inputMap }.distinct()
        if (distinctInputs.size <= 1) {
            // Nothing to discriminate between; re-evaluating one point would spend budget to learn
            // nothing about the ordering.
            logger.debug { "Screening skipped: a single distinct candidate input point" }
            return ScreeningResult(pool.take(options.topK), 0, 0, null)
        }
        val modelInputs = distinctInputs.map { inputMap ->
            ModelInputs(
                modelIdentifier = problemDefinition.modelIdentifier,
                numReplications = screeningReplications,
                inputs = inputMap,
                responseNames = problemDefinition.allResponseNames.toSet()
            )
        }
        // CRN across the screened points, for the same paired-comparison reason confirmation uses
        // it; caching must be off under CRN.
        val screened = evaluator.evaluate(
            EvaluationRequest(
                modelIdentifier = problemDefinition.modelIdentifier,
                modelInputs = modelInputs,
                crnOption = true,
                cachingAllowed = false
            )
        ).values.toList().map { solution ->
            solution.copy(
                evaluationNumber = selectionClock,
                penaltyMemory = priorMemory[solution.inputMap] ?: solution.penaltyMemory
            )
        }
        val numConfidentlyFeasible = screened.count {
            it.isResponseConstraintFeasible(options.recommendationCILevel)
        }
        if (numConfidentlyFeasible == 0) {
            logger.warn {
                "Screening at $screeningReplications replications did not restore discrimination: " +
                    "none of ${screened.size} screened candidates is confidently response-feasible " +
                    "at level ${options.recommendationCILevel}. The finalists are still ranked by " +
                    "violation alone. Size screeningReplications against the constraint."
            }
        }
        logger.debug {
            "Screened ${distinctInputs.size} candidates at $screeningReplications replications; " +
                "$numConfidentlyFeasible confidently feasible"
        }
        return ScreeningResult(
            finalists = screened.sortedWith(comparator).take(options.topK),
            numOracleCalls = modelInputs.size,
            numReplicationsRequested = modelInputs.sumOf { it.numReplications },
            numConfidentlyFeasible = numConfidentlyFeasible
        )
    }
}
