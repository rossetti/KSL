package ksl.simopt.evaluator

import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.problem.ProblemDefinition
import java.util.function.Consumer

abstract class Evaluator(
    val problemDefinition: ProblemDefinition,
    val cache: SolutionCacheIfc? = null
) {

    // the budget (in terms of number of evaluations)
    var maxReplicationBudget: Int = Int.MAX_VALUE
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

    val hasRemainingReplications: Boolean
        get() = numDirectReplications < maxReplicationBudget

    fun resetStatistics() {
        numBatches = 0
        numRequests = 0
        numReplications = 0
        numDuplicateRequestsInBatch = 0
        numDirectEvaluations = 0
        numCachedEvaluations = 0
        numDirectReplications = 0
        numCachedReplications = 0
    }

    /**
     * Required method in Evaluator subclasses to evaluate responses from list of requests.
     *
     * @param requests - a list of requests to evaluate
     * @return - a map containing responses for each request evaluated
     */
    protected abstract fun evaluateResponses(requests: List<EvaluationRequest>) : Map<EvaluationRequest, ResponseMap>

    fun evaluate(requests: List<EvaluationRequest>) : List<Solution> {
        TODO("Not implemented yet")
    }

    /**
     * Make sure we do NOT evaluate duplicate inputs within the batch
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