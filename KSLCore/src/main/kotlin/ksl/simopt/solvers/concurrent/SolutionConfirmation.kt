package ksl.simopt.solvers.concurrent

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
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
     * Ranks the candidates by penalized objective value, takes the top candidates per
     * the options, re-evaluates their distinct input points in one CRN request (no
     * caching), and returns the winner by confirmed penalized objective value.
     *
     * Invalid candidates (failed members) are excluded from ranking when at least one
     * valid candidate exists. When the finalists collapse to a single distinct input
     * point, no evaluation is performed and the best candidate is returned as-is.
     *
     * Every ranking here is taken at one clock, the furthest any candidate reached, and the
     * confirmed solutions carry the search's penalty state rather than the confirmation
     * evaluator's: confirmation supplies the estimates, not the penalty regime. The returned
     * solutions are stamped accordingly, so the penalized value a caller records is the value
     * the winner was actually chosen on.
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
        // Every ranking in this function is taken at one clock -- the furthest the search
        // reached. Members finish at different clocks, so ranking each candidate at its own
        // would judge them under unequal penalties; and the confirmation evaluator below is
        // newly constructed, so its solutions arrive at evaluation number 1, the weakest
        // penalty of the whole run. Neither is the basis on which the run's final decision
        // should be made.
        val selectionClock = candidates.maxOf { it.evaluationNumber }
        val finalists = usable
            .sortedBy { it.atEvaluation(selectionClock).penalizedObjFncValue }
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
        val winner = restamped.minByOrNull { it.penalizedObjFncValue }
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
