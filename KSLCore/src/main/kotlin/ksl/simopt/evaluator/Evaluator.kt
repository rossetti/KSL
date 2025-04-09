package ksl.simopt.evaluator

import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition

/**
 *  An evaluator should communicate with the simulation oracle to determine
 *  solutions for requests for evaluation from solvers.
 *
 *  @param problemDefinition the problem that the evaluation of responses will be used on
 *  @param simulationProvider the provider of responses from the simulation oracle
 *  @param cache a cache that can be used instead of a costly simulation evaluation
 *  @param oracleReplicationBudget the maximum number of direct replications permitted by the evaluator.
 *  The default is Int.MAX_VALUE. This can be used to control the total number of replications executed
 *  by the simulation oracle.
 */
class Evaluator(
    override val problemDefinition: ProblemDefinition,
    private val simulationProvider: SimulationProviderIfc,
    override val cache: SolutionCacheIfc? = null,
    oracleReplicationBudget: Int = Int.MAX_VALUE
) : EvaluatorIfc {
    init {
        require(oracleReplicationBudget >= 1) { "The number of budgeted replications must be >= 1" }
    }

    /**
     *   The maximum budget (in terms of number of replications) within the evaluations
     *   performed by the simulation oracle.
     */
    override var maxOracleReplicationBudget: Int = oracleReplicationBudget
        set(value) {
            require(value >= 1) { "The number of budgeted replications must be >= 1" }
            field = value
        }

    /**
     *  The total number of evaluations performed. An evaluation may have many replications.
     */
    override var totalEvaluations: Int = 0
        private set

    /**
     *  The total number of evaluations performed via the simulation oracle.
     */
    override var totalOracleEvaluations = 0
        private set

    /**
     *  The total number of evaluations performed via the cache.
     */
    override var totalCachedEvaluations = 0
        private set

    /**
     *  The total number of evaluation requests that were received.
     */
    override var totalRequestsReceived: Int = 0
        private set

    /**
     *  The total number of evaluation requests received that were duplicates in
     *  terms of inputs.
     */
    override var totalDuplicateRequestReceived: Int = 0
        private set

    /**
     *  The total number of replications requested across all evaluation requests.
     */
    override var totalReplications: Int = 0
        private set

    /**
     *  The total number of replications performed by the simulation oracle.
     */
    override var totalOracleReplications: Int = 0
        private set

    /**
     *  The total number of replications satisfied by the cache.
     */
    override var totalCachedReplications: Int = 0
        private set

    /**
     *  Indicates if the number of replications budgeted has been exceeded or not.
     */
    override val hasRemainingOracleReplications: Boolean
        get() = totalOracleReplications < maxOracleReplicationBudget

    /**
     *  The total number of remaining replications that can be performed by
     *  the simulation oracle.
     */
    override val remainingOracleReplications: Int
        get() = maxOracleReplicationBudget - totalOracleReplications

    /**
     *  The evaluator collects some basic counts (statistics) on its evaluations.
     *  This function resets all counters to 0, perhaps in preparation for another
     *  evaluation run.
     */
    override fun resetEvaluationCounts() {
        totalEvaluations = 0
        totalOracleEvaluations = 0
        totalCachedEvaluations = 0
        totalRequestsReceived = 0
        totalReplications = 0
        totalDuplicateRequestReceived = 0
        totalOracleReplications = 0
        totalCachedReplications = 0
    }

    /**
     *  Processes the supplied requests for solutions. The solutions may come from an associated
     *  solution cache (if present) or via evaluations by the simulation oracle.  The list of
     *  requests may have duplicated inputs, in which case, the solution will also be a duplicate.
     *  That is, no extra evaluations occur for duplicates in the list of requests. Any new
     *  solutions that result due to the processing will be entered into the cache (according
     *  to the rules governing the cache).
     *
     *  @param requests a list of evaluation requests
     *  @return a list containing a solution for each request
     */
    override fun evaluate(requests: List<EvaluationRequest>): List<Solution> {
        totalEvaluations++
        totalRequestsReceived = totalRequestsReceived + requests.size
        // round the requests to the appropriate granularity for the problem
        roundRequestsToGranularity(requests)
        // filter out the duplicate requests
        val uniqueRequests = filterToUniqueRequests(requests)
        totalDuplicateRequestReceived = totalDuplicateRequestReceived + (requests.size - uniqueRequests.size)
        // check with the cache for solutions
        val solutionMap = cache?.retrieveSolutions(uniqueRequests) ?: mutableMapOf()
        // the returned map is either empty or contains solutions associated with some of the requests
        // update the requests based on the replications in the solutions
        updateRequestReplicationData(solutionMap, uniqueRequests)
        // filter requests that no longer need replications
        val requestsToSimulate = uniqueRequests.filter { it.numReplications > 0 }
        // evaluate remaining requests and update solutions
        if (requestsToSimulate.isNotEmpty()) {
            //TODO since Solution contains InputMap, the association with EvaluationRequest may not be needed
            val simulatedSolutions = evaluateViaSimulation(requestsToSimulate)
            // since some requests could have needed additional replications, we may need to merge solutions
            // from the cache with solutions performed by the oracle
            for ((request, simulatedSolution) in simulatedSolutions) {
                if (solutionMap.containsKey(request.inputMap)) {
                    // merge the solution with the cached solution
                    val cachedSolution = solutionMap[request.inputMap]!!
                    solutionMap[request.inputMap] = mergeSolution(request, cachedSolution, simulatedSolution)
                } else {
                    solutionMap[request.inputMap] = simulatedSolution
                }
            }
            // update the cache with any new solutions after possible merging
            if (cache != null){
                for((inputMap, solution) in solutionMap){
                    cache[inputMap] = solution
                }
            }
        }
        // package the solutions up for each request in the order that was requested
        // handle duplicate input requests by grabbing from the solution map based on the input of the request
        val solutions = mutableListOf<Solution>()
        for(request in requests){
            solutions.add(solutionMap[request.inputMap]!!)
        }
        return solutions
    }

    /**
     *  Because some replications can be satisfied by the cache,
     *  this function updates the request's original amount requested
     *  so that the simulation oracle does not need to run those replications.
     *  @param solutionMap the solutions obtained from the cache
     *  @param uniqueRequests the requests that need evaluation
     */
    private fun updateRequestReplicationData(
        solutionMap: MutableMap<InputMap, Solution>,
        uniqueRequests: List<EvaluationRequest>
    ) {
        if (solutionMap.isNotEmpty()) {
            return
        }
        for (request in uniqueRequests) {
            val sol = solutionMap[request.inputMap]
            if (sol != null) {
                val n = sol.numReplications
                totalCachedEvaluations++
                totalCachedReplications = totalCachedReplications + n
                request.startingReplicationNum = n //TODO why?
                request.numReplications = request.numReplications - n
            }
        }
    }

    /**
     *  A helper function to round the inputs associated with each request to the
     *  necessary granularity associated with the problem. The input map
     *  associated with the evaluation request will be mutated to the correct granularity.
     *  This is important because the values of the inputs determine if new evaluations
     *  are necessary. We simulate at the required granularity.
     */
    private fun roundRequestsToGranularity(requests: List<EvaluationRequest>) {
        for (request in requests) {
            problemDefinition.roundToGranularity(request.inputMap)
        }
    }

    /**
     * Evaluate directly via the simulation oracle (without accessing the cache).
     *
     * @param requests  list of requests (design vector input and number of desired replications)
     * @return a map of evaluation requests with their accompanying solution
     */
    private fun evaluateViaSimulation(
        requests: List<EvaluationRequest>
    ): Map<EvaluationRequest, Solution> {
        require(requests.isNotEmpty()) { "Cannot evaluate a list of empty requests!" }
        totalOracleEvaluations = totalOracleEvaluations + requests.size
        totalOracleReplications = totalOracleReplications + requests.totalReplications()
        // run the evaluations
        val cases = simulationProvider.runSimulations(requests)
        val solutions: MutableMap<EvaluationRequest, Solution> = mutableMapOf()
        // Converts (EvaluationRequest, ResponseMap) pairs to (EvaluationRequest, Solution)
        for ((request, responseMap) in cases) {
            solutions[request] = createSolution(request, responseMap)
        }
        return solutions
    }

    /**
     *  Converts the response map to an instance of a Solution based
     *  on the supplied evaluation request.
     *
     *  @param request the associated request
     *  @param responseMap the response map to convert
     */
    private fun createSolution(
        request: EvaluationRequest,
        responseMap: ResponseMap,
    ): Solution {
        val objFnName = problemDefinition.objFnResponseName
        val estimatedObjFnc = responseMap[objFnName]!!
        val responseEstimates = mutableListOf<EstimatedResponse>()
        for ((name, _) in responseMap) {
            if (name != objFnName) {
                val estimate = responseMap[name]!!
                responseEstimates.add(estimate)
            }
        }
        val solution = Solution(
            request.inputMap,
            request.numReplications,
            estimatedObjFnc,
            responseEstimates,
            totalEvaluations
        )
        return solution
    }

    /**
     *  Merges the current solution with the provided solution to
     *  produce a new combined (merged) solution. The existing solution
     *  and the supplied solution are not changed during the merging process.
     */
    private fun mergeSolution(
        request: EvaluationRequest,
        firstSolution: Solution,
        secondSolution: Solution,
    ): Solution {
        require(firstSolution.inputMap == secondSolution.inputMap) { "The inputs must be the same in order to merge the solutions" }
        require(firstSolution.responseEstimates.size == secondSolution.responseEstimates.size) { "Cannot merge solutions with different response sizes" }
        // We assume that the two solutions are from independent replications
        // convert and merge as response maps
        val r1 = firstSolution.toResponseMap()
        val r2 = secondSolution.toResponseMap()
        // merge them
        r1.mergeAll(r2)
        return createSolution(request, r1)
    }

    companion object {

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
        fun filterToUniqueRequests(requests: List<EvaluationRequest>): List<EvaluationRequest> {
            val uniqueRequests = mutableSetOf<EvaluationRequest>()
            // since requests are the same based on the values of their input maps
            // we need only update the duplicate so that it has the maximum of any duplicate entries
            for (req in requests) {
                if (uniqueRequests.contains(req)) {
                    req.maxOfReplication(req.numReplications)
                } else {
                    uniqueRequests.add(req)
                }
            }
            return uniqueRequests.toList()
        }

    }
}

/**
 *  A simple extension function to compute the total number of replications within a set
 *  of evaluation requests.
 */
fun List<EvaluationRequest>.totalReplications(): Int {
    return sumOf { it.numReplications }
}