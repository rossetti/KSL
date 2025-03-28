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
    var totalEvaluations: Int = 0
        protected set
    // the number of evaluations with a direct component
    var totalDirectEvaluations = 0
        protected set
    // the number of evaluations with a cached component
    var totalCachedEvaluations = 0
        protected set
    // the total number of evaluations
    var totalRequestsReceived: Int = 0
        protected set
    // the number of evaluations that were avoided as duplicates within a batch
    var totalDuplicateRequestReceived: Int = 0
        protected set
    // the total number of replications
    var numReplications: Int = 0
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
        totalEvaluations = 0
        totalDirectEvaluations = 0
        totalCachedEvaluations = 0
        totalRequestsReceived = 0
        numReplications = 0
        totalDuplicateRequestReceived = 0
        numDirectReplications = 0
        numCachedReplications = 0
    }

    fun evaluate(requests: List<EvaluationRequest>) : List<Solution> {
        totalEvaluations++
        totalRequestsReceived = totalRequestsReceived + requests.size
        // round the requests to the appropriate granularity for the problem
        roundRequestToGranularity(requests)
        // filter out the duplicate requests
        val uniqueRequests = filterToUniqueRequests(requests)
        totalDuplicateRequestReceived = totalDuplicateRequestReceived + (requests.size - uniqueRequests.size)
        // check with the cache for solutions

        //TODO

        // evaluate remaining requests

        //TODO

        // package up the solutions

        TODO("Not implemented yet")
    }

    private fun roundRequestToGranularity(requests: List<EvaluationRequest>){
        for(request in requests){
            problemDefinition.roundToGranularity(request.inputMap)
        }
    }

    /**
     *  The list of requests may come from different solvers. The requests may have the
     *  same design point (inputs). If so, we need to remove any requests for
     *  the same inputs, and keep the duplicate that has the maximum number of replications.
     *  This ensures that the evaluation covers any request with fewer replications and
     *  does not repeat expensive simulation evaluations on the same input values.
     *
     * @param requests a list of evaluation requests
     * @return a list of evaluation requests that are unique
     */
    private fun filterToUniqueRequests(requests: List<EvaluationRequest>): List<EvaluationRequest> {
        val uniqueRequests = mutableSetOf<EvaluationRequest>()
        // since requests are the same based on the values of their input maps
        // we need only update the duplicate so that it has the maximum of any duplicate entries
        for (req in requests) {
            if (uniqueRequests.contains(req)){
                req.maxOfReplication(req.numReplications)
            } else {
                uniqueRequests.add(req)
            }
        }
        return uniqueRequests.toList()
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