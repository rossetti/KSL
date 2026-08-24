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
 */
data class ConfirmationOutcome(
    val winner: Solution,
    val confirmedSolutions: List<Solution>,
    val numOracleCalls: Int,
    val numReplicationsRequested: Int
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
        val recommendationComparator = FeasibilityFirstComparator(options.recommendationCILevel)
        val selectionClock = candidates.maxOf { it.evaluationNumber }
        val finalists = usable
            .sortedWith(recommendationComparator)
            .take(options.topK)
        val distinctInputs = finalists.map { it.inputMap }.distinct()
        if (distinctInputs.size <= 1) {
            logger.debug { "Confirmation skipped: a single distinct finalist input point" }
            return ConfirmationOutcome(finalists.first(), emptyList(), 0, 0)
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
        // that sample than it can support.
        val priorMemory = LinkedHashMap<InputMap, Map<String, PenaltyMemory>>()
        for (finalist in finalists) {
            // first-wins, matching the rule distinctInputs used to collapse duplicate points
            priorMemory.putIfAbsent(finalist.inputMap, finalist.penaltyMemory)
        }
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
            numOracleCalls = modelInputs.size,
            numReplicationsRequested = modelInputs.sumOf { it.numReplications }
        )
    }
}
