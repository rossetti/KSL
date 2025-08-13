package ksl.simopt.evaluator

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.problem.ProblemDefinition

interface EvaluatorIfc {

    /**
     *  The problem definition associated with the evaluation process
     */
    val problemDefinition: ProblemDefinition

    /**
     *  A possible cache to hold evaluated solutions
     */
    val cache: SolutionCacheIfc? //TODO move to Evaluator

    /**
     *   The maximum budget (in terms of number of replications) within the evaluations
     *   performed by the simulation oracle.
     */
    val maxOracleReplicationBudget: Int //TODO delete

    /**
     *  The total number of evaluations performed. An evaluation may have many replications.
     */
    val totalEvaluations: Int //TODO move to Evaluator

    /**
     *  The total number of evaluations performed via the simulation oracle.
     */
    val totalOracleEvaluations: Int //TODO move to Evaluator

    /**
     *  The total number of evaluations performed via the cache.
     */
    val totalCachedEvaluations: Int //TODO move to Evaluator

    /**
     *  The total number of evaluation requests that were received.
     */
    val totalRequestsReceived: Int //TODO move to Evaluator

    /**
     *  The total number of evaluation requests received that were duplicates in
     *  terms of inputs.
     */
    val totalDuplicateRequestReceived: Int //TODO move to Evaluator

    /**
     *  The total number of replications requested across all evaluation requests.
     */
    val totalReplications: Int //TODO move to Evaluator

    /**
     *  The total number of replications performed by the simulation oracle.
     */
    val totalOracleReplications: Int //TODO move to Evaluator

    /**
     *  The total number of replications satisfied by the cache.
     */
    val totalCachedReplications: Int //TODO move to Evaluator

    /**
     *  Indicates if the number of replications budgeted has been exceeded or not.
     */
    val hasRemainingOracleReplications: Boolean //TODO delete

    /**
     *  The total number of remaining replications that can be performed by
     *  the simulation oracle.
     */
    val remainingOracleReplications: Int //TODO delete

    /**
     *  The evaluator collects some basic counts (statistics) on its evaluations.
     *  This function resets all counters to 0, perhaps in preparation for another
     *  evaluation run.
     */
    fun resetEvaluationCounts() //TODO move to Evaluator

    /**
     *  Processes the supplied requests for solutions. The solutions may come from an associated
     *  solution cache (if present) or via evaluations by the simulation oracle.  The list of
     *  requests may have duplicated inputs, in which case, the solution will also be a duplicate.
     *  That is, no extra evaluations occur for duplicates in the list of requests. Any new
     *  solutions that result due to the processing will be entered into the cache (according
     *  to the rules governing the cache).  Any incoming requests that have input range
     *  infeasible input settings will not be evaluated and will result in solutions that
     *  are bad and infeasible.
     *
     *  @param rawRequests a list of evaluation requests
     *  @return a list containing a solution for each request
     */
    fun evaluate(rawRequests: List<RequestData>): List<Solution>

    /**
     *  Processes the supplied request for a solution. The solution may come from an associated
     *  solution cache (if present) or via an evaluation by the simulation oracle.
     *  A solution that results due to the processing will be entered into the cache (according
     *  to the rules governing the cache).  If the request is input range-infeasible,
     *  then a bad and infeasible solution will be returned.
     *
     *  @param request a request needing evaluation
     *  @return the solution associated with the request
     */
    fun evaluate(request: RequestData): Solution {
        return evaluate(listOf(request)).first()
    }

    companion object {

        @JvmStatic
        val logger: KLogger = KotlinLogging.logger {}

    }
}