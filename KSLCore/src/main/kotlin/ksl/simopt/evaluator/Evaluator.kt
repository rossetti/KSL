package ksl.simopt.evaluator

import ksl.simopt.cache.MemorySolutionCache
import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.MapModelProvider
import ksl.simulation.ModelBuilderIfc
import org.jetbrains.letsPlot.core.spec.remove

/**
 *  An evaluator should communicate with the simulation oracle to determine
 *  solutions for requests for evaluation from solvers.
 *
 *  @param problemDefinition the problem that the evaluation of responses will be used on
 *  @param simulator the provider of responses from the simulation oracle
 *  @param cache a cache that can be used instead of a costly simulation evaluation
 *  @param oracleReplicationBudget the maximum number of direct replications permitted by the evaluator.
 *  The default is Int.MAX_VALUE. This can be used to control the total number of replications executed
 *  by the simulation oracle.
 */
class Evaluator @JvmOverloads constructor(
    override val problemDefinition: ProblemDefinition,
    private val simulator: RunSimulationsForResponseMapsIfc,
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
    override var totalOracleEvaluations: Int = 0
        private set

    /**
     *  The total number of evaluations performed via the cache.
     */
    override var totalCachedEvaluations: Int = 0
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
    override val totalReplications: Int
        get() = totalOracleReplications + totalCachedReplications

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
        EvaluatorIfc.logger.trace { "Resetting evaluator counts" }
        totalEvaluations = 0
        totalOracleEvaluations = 0
        totalCachedEvaluations = 0
        totalRequestsReceived = 0
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
     *  @param rawRequests a list of evaluation requests
     *  @return a list containing a solution for each request
     */
    override fun evaluate(rawRequests: List<RequestData>): List<Solution> {
        //TODO the new cache work is not working!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
        EvaluatorIfc.logger.trace { "Evaluating ${rawRequests.size} requests" }
        totalEvaluations++
        totalRequestsReceived = totalRequestsReceived + rawRequests.size
        EvaluatorIfc.logger.trace { "Total Evaluations $totalEvaluations, total requests received $totalRequestsReceived" }
        // Filter out the duplicate requests. This also returns the requests that have the most replications.
        val uniqueRequests = filterToUniqueRequests(rawRequests)
        totalDuplicateRequestReceived = totalDuplicateRequestReceived + (rawRequests.size - uniqueRequests.size)
        EvaluatorIfc.logger.trace { "Total total duplicate requests received $totalDuplicateRequestReceived" }
        // check with the cache for solutions
        //TODO this is the check of the cache, what if the cache does not hold solutions
        val solutionMap = cache?.retrieveSolutions(uniqueRequests) ?: mutableMapOf()
        EvaluatorIfc.logger.trace { "Solutions found in the cache: ${solutionMap.size}" }
        // the returned map is either empty or contains solutions associated with some requests
        // update and filter the requests based on the replications in the solution cache
        //TODO the new cache work is not working!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
        val requestsToSimulate = reviseRequests(solutionMap, uniqueRequests)
        EvaluatorIfc.logger.trace { "Requests to simulate: ${requestsToSimulate.size}" }
        // evaluate remaining requests and update solutions
        if (requestsToSimulate.isNotEmpty()) {
            //TODO since Solution contains InputMap, the association with EvaluationRequest may not be needed
            val simulatedSolutions = evaluateViaSimulation(requestsToSimulate)
            EvaluatorIfc.logger.trace { "Requests simulated, resulting in ${simulatedSolutions.size} solutions" }
            // since some requests could have needed additional replications, we may need to merge solutions
            // from the cache with solutions performed by the oracle
            for ((request, simulatedSolution) in simulatedSolutions) {
                if (solutionMap.containsKey(request)) {
                    // merge the solution with the cached solution
                    EvaluatorIfc.logger.trace { "Merging solution with cached solution in solution map." }
                    val cachedSolution = solutionMap[request]!!
                    solutionMap[request] = mergeSolution(request, cachedSolution, simulatedSolution)
                } else {
                    EvaluatorIfc.logger.trace { "Adding solution to the solution map without merging with cached solution." }
                    solutionMap[request] = simulatedSolution
                }
            }
            // update the cache with any new solutions after possible merging
            if (cache != null) {
                EvaluatorIfc.logger.trace { "Updating cache with ${solutionMap.size} solutions" }
                for ((inputMap, solution) in solutionMap) {
                    cache[inputMap] = solution
                }
            }
        }
        // package the solutions up for each request in the order that was requested
        // handle the duplicate input requests by grabbing from the solution map based on the input of the request
        val solutions = mutableListOf<Solution>()
        for (request in rawRequests) {
            solutions.add(solutionMap[request]!!)
        }
        EvaluatorIfc.logger.trace { "Packaged ${solutions.size} solutions for return" }
        return solutions
    }

    /**
     *  Because the cache can satisfy some replications,
     *  this function updates the request's original amount requested
     *  so that the simulation oracle does not need to run those replications.
     *  It also filters out any requests that can be fully satisfied from the cache.
     *
     *  @param cachedSolutions the solutions obtained from the cache
     *  @param uniqueRequests the requests that need evaluation
     */
    private fun reviseRequests(
        cachedSolutions: MutableMap<RequestData, Solution>,
        uniqueRequests: List<RequestData>
    ): List<RequestData> {
        //TODO the new cache work is not working!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
        // The cached solutions map has the solutions that are associated with the requests.
        // The uniqueRequests list holds the possible requests that could be simulated.
        if (cachedSolutions.isEmpty()) {
            // If there are no solutions in the cache for the provided possible requests,
            // then there is nothing revise in the list.
            return uniqueRequests
        }
        // There are some cached solutions that need to be reviewed.
        // Make it easier to look up the request data by the request's hash code/equals methods.
        val possibleRequests = uniqueRequests.associateBy { it }.toMutableMap()
        // Process the requests in the cache
        //TODO the new cache work is not working!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
        for ((request, solution) in cachedSolutions) {
            if (possibleRequests.contains(request)) {
                // The request will be satisfied wholly or in-part via the cache.
                totalCachedEvaluations++
                // The request was found. Need to assess the situation.
                // Get the actual new request for simulation runs.
                val possibleRequest = possibleRequests[request]!!
                // Determine the number of replications stored in the cache for the associated solution.
                val numRepsInCache = solution.count.toInt()
                if (numRepsInCache >= possibleRequest.numReplications) {
                    //Remove the item from the possible requests because there is no need to simulate it.
                    // All the replications will be satisfied by the cache.
                    totalCachedReplications = totalCachedReplications + possibleRequest.numReplications
                    possibleRequests.remove(request)
                } else {
                    // The cached solution doesn't have enough replications. We need to simulate more replications.
                    val requiredReps = possibleRequest.numReplications - numRepsInCache
                    // make the new request
                    val updatedRequest = possibleRequest.instance(requiredReps)
                    // update it in the map
                    possibleRequests[updatedRequest] = updatedRequest
                    // part of the replications will be satisfied by the cache
                    totalCachedReplications = totalCachedReplications + numRepsInCache
                }
            }
        }
        return possibleRequests.keys.toList()
    }

    /**
     * Evaluate directly via the simulation oracle (without accessing the cache).
     *
     * @param requests  list of requests (design vector input and number of desired replications)
     * @return a map of evaluation requests with their accompanying solution
     */
    private fun evaluateViaSimulation(
        requests: List<RequestData>
    ): Map<RequestData, Solution> {
        require(requests.isNotEmpty()) { "Cannot evaluate a list of empty requests!" }
        totalOracleEvaluations = totalOracleEvaluations + requests.size
        totalOracleReplications = totalOracleReplications + requests.totalReplications()
        // run the evaluations
        //TODO this is the long-running task
        val cases = simulator.runSimulationsForResponseMaps(requests)
        val solutions: MutableMap<RequestData, Solution> = mutableMapOf()
        // Converts (EvaluationRequest, ResponseMap) pairs to (EvaluationRequest, Solution)
        for ((request, result) in cases) {
            solutions[request] = if (result.isFailure) {
                problemDefinition.badSolution()
            } else {
                createSolution(request, result.getOrNull()!!)
            }
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
        request: RequestData,
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
        // Need to make the InputMap. This will not be the same object used to make the request.
        val inputMap = InputMap(problemDefinition, request.inputs.toMutableMap())
        val solution = Solution(
            inputMap,
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
        request: RequestData,
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

    override fun toString(): String {
        val sb = StringBuilder().apply {
            appendLine("Evaluator:")
            appendLine("maxOracleReplicationBudget = $maxOracleReplicationBudget")
            appendLine("totalEvaluations = $totalEvaluations")
            appendLine("totalOracleEvaluations = $totalOracleEvaluations")
            appendLine("totalCachedEvaluations = $totalCachedEvaluations")
            appendLine("totalRequestsReceived = $totalRequestsReceived")
            appendLine("totalDuplicateRequestReceived = $totalDuplicateRequestReceived")
            appendLine("totalCachedReplications = $totalCachedReplications")
            appendLine("totalOracleReplications = $totalOracleReplications")
            appendLine("totalReplications = $totalReplications")
            appendLine()
            appendLine("Problem Definition:")
            append("$problemDefinition")
        }
        return sb.toString()
    }


    companion object {

        /**
         *  The list of requests may come from different solvers. The requests may have the
         *  same design point (inputs). If so, we need to remove any requests for
         *  the same inputs and keep the duplicate that has the maximum number of replications.
         *  This ensures that the evaluation covers any request with fewer replications and
         *  does not repeat expensive simulation evaluations on the same input values.
         *
         * @param requests a list of evaluation requests
         * @return a list of evaluation requests that are unique
         */
        @JvmStatic
        fun filterToUniqueRequests(requests: List<RequestData>): List<RequestData> {
            val uniqueRequests = mutableMapOf<RequestData, RequestData>()

            // Since requests are the same based on the values of their input maps,
            // we need only update the duplicate so that it has the maximum of any duplicate entries.
            for (possibleRequest in requests) {
                if (uniqueRequests.contains(possibleRequest)) {
                    // If the uniqueRequests already contains the possible request, then this means
                    // that the possible request is a duplicate of the request already placed in the map.
                    // If the new (possible request) has  more replications than the one stored, we need to replace the entry.
                    val storedRequest =
                        uniqueRequests[possibleRequest]!! //because of the contains() check, it must be there
                    if (possibleRequest.numReplications > storedRequest.numReplications) {
                        // replace it with the one that has more replications
                        uniqueRequests[possibleRequest] = possibleRequest
                    }
                } else {
                    uniqueRequests[possibleRequest] = possibleRequest
                }
            }
            return uniqueRequests.keys.toList()
        }

        /**
         * Creates an instance of an `Evaluator` for a given problem definition and simulation model
         * which uses a memory-based solution cache to improve the efficiency of evaluations. The
         * simulation execution is based on a SimulationProvider, which runs locally in the same
         * thread as the evaluator.  During the evaluation process the same model is used repeatedly.
         *
         * @param problemDefinition Represents the definition of the problem, including the objectives,
         *                          constraints, and other domain-specific configurations required for evaluation.
         * @param modelBuilder a builder instance responsible for constructing the simulation model
         *                     used during problem evaluation.
         * @param modelConfiguration A map of strings representing the model configuration. The key string
         * should contain the necessary information for being able to use the paired string value.
         * The stored string values could be anything. For example, the value could be a JSON
         * string and the key provides information about how to process the JSON.
         * The intent is that the map should be sufficient to build an appropriate `Model` instance.
         * The map is optional. The function should return a model that is usable.
         * @return An `Evaluator` instance configured with the specified problem definition, simulation provider,
         *         and a memory-based solution cache.
         * @throws IllegalArgumentException if the problem definition and the model are not input/response compatible,
         * use ProblemDefinition.validateProblemDefinition to check.
         */
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun createProblemEvaluator(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            modelConfiguration: Map<String, String>? = null
        ): Evaluator {
            val model = modelBuilder.build(modelConfiguration)
            require(problemDefinition.validateProblemDefinition(model)) { "The problem definition and the model are not input/response compatible." }
            val simulationProvider = SimulationProvider(model)
            return Evaluator(problemDefinition, simulationProvider, MemorySolutionCache())
        }

        /**
         * Creates an instance of `Evaluator` for managing simulation service problem evaluation.
         * The method validates the compatibility of the problem definition with the provided model,
         * constructs a simulator using the model, and initializes the evaluator with a solution cache.
         *
         * @param problemDefinition the definition of the problem being evaluated; must be compatible
         *                          with the provided model.
         * @param modelIdentifier an identifier used to distinguish between different simulation models.
         * @param modelBuilder a builder instance responsible for constructing the simulation model
         *                     used during problem evaluation.
         * @param modelConfiguration A map of strings representing the model configuration. The key string
         * should contain the necessary information for being able to use the paired string value.
         * The stored string values could be anything. For example, the value could be a JSON
         * string and the key provides information about how to process the JSON.
         * The intent is that the map should be sufficient to build an appropriate `Model` instance.
         * The map is optional. The function should return a model that is usable.
         * @return an `Evaluator` instance configured with the given problem definition and simulation environment.
         * @throws IllegalArgumentException if the problem definition and model are not input/response compatible.
         */
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun createSimulationServiceProblemEvaluator(
            problemDefinition: ProblemDefinition,
            modelIdentifier: String,
            modelBuilder: ModelBuilderIfc,
            modelConfiguration: Map<String, String>? = null
        ): Evaluator {
            val model = modelBuilder.build(modelConfiguration)
            require(problemDefinition.validateProblemDefinition(model)) { "The problem definition and the model are not input/response compatible." }
            val mapModelProvider = MapModelProvider(modelIdentifier, modelBuilder)
            val simulator = SimulationService(mapModelProvider)
            return Evaluator(problemDefinition, simulator, MemorySolutionCache())
        }

    }
}

/**
 *  A simple extension function to compute the total number of replications within a set
 *  of evaluation requests.
 */
fun List<RequestData>.totalReplications(): Int {
    return sumOf { it.numReplications }
}