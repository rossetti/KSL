package ksl.simopt.evaluator

import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.problem.ProblemDefinition

/**
 *  A functional interface that promises to convert a list of requests for evaluation
 *  into responses associated with the requests. Used within [Evaluator] to
 *  communicated with the simulation oracle.
 */
fun interface ResponseProviderIfc {

    /**
     * Promises to convert evaluation requests into responses.
     *
     * @param requests - a list of requests to evaluate
     * @return - a map containing responses for each request evaluated
     */
    fun provideResponses(
        requests: List<EvaluationRequest>
    ) : Map<EvaluationRequest, ResponseMap>

}

/**
 *  An evaluator should communicate with the simulation oracle to determine
 *  solutions for requests for evaluation from solvers.
 *
 *  @param problemDefinition the problem that the evaluation of responses will be used on
 *  @param responseProvider the provider of responses from the simulation oracle
 *  @param cache a cache that can be used instead of a costly simulation evaluation
 *  @param replicationBudget the maximum number of direct replications permitted by the evaluator.
 *  The default is Int.MAX_VALUE. This can be used to control the total number of evaluation.
 */
class Evaluator(
    val problemDefinition: ProblemDefinition,
    private val responseProvider: ResponseProviderIfc,
    val cache: SolutionCacheIfc? = null,
    replicationBudget: Int = Int.MAX_VALUE
) {
    init {
        require(replicationBudget >= 1) {"The number of budgeted replications must be >= 1"}
    }

    // the budget (in terms of number of evaluations)
    var maxReplicationBudget: Int = replicationBudget
        set(value){
            require(value >= 1) {"The number of budgeted replications must be >= 1"}
            field = value
        }

    // the total number of batches evaluated
    var numBatches: Int = 0
        protected set
    // the total number of evaluations
    var numRequests: Int = 0
        protected set
    // the total number of replications
    var numReplications: Int = 0
        protected set
    // the number of evaluations that were avoided as duplicates within a batch
    var numDuplicateRequestsInBatch: Int = 0
        protected set
    // the number of evaluations with a direct component
    var numDirectEvaluations = 0
        protected set
    // the number of evaluations with a cached component
    var numCachedEvaluations = 0
        protected set
    // the number of evaluation replications that did not come from the cache
    var numDirectReplications: Int = 0
        protected set
    // the number of evaluations replications that came from the cache
    var numCachedReplications: Int = 0
        protected set

    /**
     *  Indicates if the number of replications budgeted has been exceeded or not.
     */
    val hasRemainingReplications: Boolean
        get() = numDirectReplications < maxReplicationBudget

    /**
     *  The evaluator collects some basic counts (statistics) on its evaluations.
     *  This function resets all counters to 0, perhaps in preparation for another
     *  evaluation run.
     */
    fun resetEvaluationCounts() {
        numBatches = 0
        numRequests = 0
        numReplications = 0
        numDuplicateRequestsInBatch = 0
        numDirectEvaluations = 0
        numCachedEvaluations = 0
        numDirectReplications = 0
        numCachedReplications = 0
    }

    fun evaluate(requests: List<EvaluationRequest>) : List<Solution> {
        TODO("Not implemented yet")
    }

    /**
     * Make sure we do not evaluate duplicate inputs within the batch
     * where multiple requests for the same design vector are made but for different
     * numbers of replications the larger replication number is chosen.
     * @param requests a list of evaluation requests
     * @return a list of evaluation requests that are unique
     */
    private fun findUniqueRequests(requests: List<EvaluationRequest>): List<EvaluationRequest> {
        TODO("Not implemented yet")
    }

    /**
     * Evaluate directly (without accessing the cache).
     *
     * @param requests  list of requests (design vector input and replications)
     * @return a map of evaluation requests with their accompanying solution
     */
    private fun evaluateWithNoCache(requests: List<EvaluationRequest>): Map<EvaluationRequest, Solution> {
        TODO("Not implemented yet")
    }

    /**
     * Match solutions to raw responses by key, parse and merge
     * @param requests - evaluation requests
     * @param rawResponses - a map of raw responses (Key, Value) pairs to decode, keycode
     * is the hashcode of the solution that was evaluated.
     * @return - solution and responses matched and parsed
     */
    private fun parseResponses(
        requests: List<EvaluationRequest>,
        rawResponses: Map<EvaluationRequest, ResponseMap>
    ): Map<EvaluationRequest, Solution> {
        TODO("Not implemented yet")
    }

    /**
     * Parse an input vector and ResponseMap into a Solution
     * throwing an error if the objective function or a required response
     * is NOT found
     * @param request a request for evaluation
     * @param responseMap - a map of raw responses (the key is a hashcode of the input/inputs)
     * @return a parsed response as a solution
     */
    private fun parseResponse(request: EvaluationRequest, responseMap: ResponseMap): Solution {
        TODO("Not implemented yet")
    }
}