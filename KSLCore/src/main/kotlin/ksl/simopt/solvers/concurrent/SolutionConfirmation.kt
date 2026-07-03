package ksl.simopt.solvers.concurrent

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.Solution
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
        val finalists = usable.sortedBy { it.penalizedObjFncValue }.take(options.topK)
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
        val winner = confirmed.minByOrNull { it.penalizedObjFncValue }
            ?: finalists.first()
        logger.debug { "Confirmation of ${distinctInputs.size} finalists complete" }
        return ConfirmationOutcome(
            winner = winner,
            confirmedSolutions = confirmed,
            numOracleCalls = modelInputs.size,
            numReplicationsRequested = modelInputs.sumOf { it.numReplications }
        )
    }
}
