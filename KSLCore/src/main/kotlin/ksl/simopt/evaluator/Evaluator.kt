package ksl.simopt.evaluator

import ksl.simopt.cache.MemorySolutionCache
import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.MapModelProvider
import ksl.simulation.ModelBuilderIfc

/**
 *  An evaluator should communicate with the simulation oracle to determine
 *  solutions for requests for evaluation from solvers.
 *
 *  @param problemDefinition the problem that the evaluation of responses will be used on
 *  @param simulator the provider of responses from the simulation oracle
 *  @param cache a cache that can be used instead of a costly simulation evaluation
 */
class Evaluator @JvmOverloads constructor(
    val problemDefinition: ProblemDefinition,
    private val simulator: SimulationOracleIfc,
    override val cache: SolutionCacheIfc? = null,
) : EvaluatorIfc {

    /**
     *  The total number of evaluations performed. An evaluation may have many replications.
     */
    var totalEvaluations: Int = 0
        private set

    /**
     *  The total number of evaluations performed via the simulation oracle.
     */
    var totalOracleEvaluations: Int = 0
        private set

    /**
     *  The total number of evaluations performed via the cache.
     */
    var totalCachedEvaluations: Int = 0
        private set

    /**
     *  The total number of evaluation requests that were received.
     */
    var totalRequestsReceived: Int = 0
        private set

    /**
     *  The total number of replications requested across all evaluation requests.
     */
    val totalReplications: Int
        get() = totalOracleReplications + totalCachedReplications

    /**
     *  The total number of replications performed by the simulation oracle.
     */
    var totalOracleReplications: Int = 0
        private set

    /**
     *  The total number of replications satisfied by the cache.
     */
    var totalCachedReplications: Int = 0
        private set

    /**
     *  The evaluator collects some basic counts (statistics) on its evaluations.
     *  This function resets all counters to 0, perhaps in preparation for another
     *  evaluation run.
     */
    @Suppress("unused")
    fun resetEvaluationCounts() {
        EvaluatorIfc.logger.trace { "Resetting evaluator counts" }
        totalEvaluations = 0
        totalOracleEvaluations = 0
        totalCachedEvaluations = 0
        totalRequestsReceived = 0
        totalOracleReplications = 0
        totalCachedReplications = 0
    }

    override fun evaluate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Solution> {
        EvaluatorIfc.logger.trace { "Evaluator: evaluate() : $evaluationRequest" }
        totalEvaluations++
        totalRequestsReceived = totalRequestsReceived + evaluationRequest.modelInputs.size
        if (evaluationRequest.crnOption || !evaluationRequest.cachingAllowed) {
            // The provider should handle the CRN and or simulate the requests with no caching.
            // For CRN, we don't do caching even if the cache exists because we don't want to mix dependent results.
            EvaluatorIfc.logger.trace { "Evaluator: evaluating via simulation: crnOption = ${evaluationRequest.crnOption} : cachingAllowed = ${evaluationRequest.cachingAllowed}" }
            return evaluateViaSimulation(evaluationRequest)
        }
        // caching could be allowed and there is no CRN
        if (cache != null) {
            EvaluatorIfc.logger.trace { "Evaluator: attempting cache based evaluation" }
            return cacheBasedEvaluation(evaluationRequest)
        }
        // there is no cache to worry about, just simulate and return
        EvaluatorIfc.logger.trace { "Evaluator: evaluating via simulation with no caching." }
        return evaluateViaSimulation(evaluationRequest)
    }

    private fun cacheBasedEvaluation(evaluationRequest: EvaluationRequest): Map<ModelInputs, Solution> {
        require(cache != null) { "The cache must not be null for cache based evaluation" }
        // check with the cache for solutions
        val cachedSolutions = cache.retrieveSolutions(evaluationRequest.modelInputs)
        EvaluatorIfc.logger.trace { "Number of solutions found in the cache: ${cachedSolutions.size}" }
        if (cachedSolutions.isEmpty()) {
            val evaluations = evaluateViaSimulation(evaluationRequest)
            // Put the solutions into the cache
            cache.putAll(evaluations)
            return evaluations
        }
        // The cache retrieved solutions are associated with some requests. The following code will add to
        // the cached solutions by simulating the requests not in the cache and those that require additional replications.
        val requests = evaluationRequest.modelInputs
        // Revise and filter the requests based on the replications in the solution cache.
        val requestsToSimulate = reviseRequestReplications(cachedSolutions, requests)
        // Since the cache could satisfy all request requirements, there may not be any requests to simulate.
        if (requestsToSimulate.isNotEmpty()) {
            // There are requests to simulate. Make a new EvaluationRequest for the revised requests.
            val revisedEvaluationRequest = evaluationRequest.instance(requestsToSimulate)
            // Simulate the revised request.
            val simulatedSolutions = evaluateViaSimulation(revisedEvaluationRequest)
            EvaluatorIfc.logger.trace { "Requests simulated, resulting in ${simulatedSolutions.size} solutions" }
            // since some requests could have needed additional replications, we may need to merge solutions
            // from the cache with solutions performed by the oracle
            for ((request, simulatedSolution) in simulatedSolutions) {
                if (cachedSolutions.containsKey(request)) {
                    // merge the solution with the cached solution
                    EvaluatorIfc.logger.trace { "Merging solution with cached solution in solution map." }
                    val cachedSolution = cachedSolutions[request]!!
                    cachedSolutions[request] = mergeSolution(request, cachedSolution, simulatedSolution)
                } else {
                    EvaluatorIfc.logger.trace { "Adding solution to the solution map without merging with cached solution." }
                    cachedSolutions[request] = simulatedSolution
                }
            }
            // update the cache with the new or updated solutions after possible merging
            cache.putAll(cachedSolutions)
        }
        return cachedSolutions
    }

    /**
     *  Because the cache can satisfy some replications,
     *  this function updates the request's original amount requested
     *  so that the simulation oracle does not need to run those replications.
     *  It also filters out any requests that can be fully satisfied from the cache.
     *
     *  @param cachedSolutions the solutions obtained from the cache
     *  @param modelInputs the model inputs that need evaluation
     */
    private fun reviseRequestReplications(
        cachedSolutions: MutableMap<ModelInputs, Solution>,
        modelInputs: List<ModelInputs>
    ): List<ModelInputs> {
        // The cached solutions map has the solutions that are associated with the requests.
        // The modelInputs list holds the possible requests that could be simulated.
        if (cachedSolutions.isEmpty()) {
            // If there are no solutions in the cache for the provided possible requests,
            // then there is nothing to revise in the list.
            return modelInputs
        }
        // There are some cached solutions that need to be reviewed.
        val revisedModelInputs = mutableListOf<ModelInputs>()
        for (modelInput in modelInputs) {
            val cachedSolution = cachedSolutions[modelInput]
            if (cachedSolution != null) {
                // Found the model input in the cache. Need to update it.
                // Determine the number of replications stored in the cache for the associated solution.
                val numRepsInCache = cachedSolution.count.toInt()
                if (numRepsInCache >= modelInput.numReplications) {
                    // The cache will satisfy all the replications. There is no need to include the input request in the revised inputs.
                    totalCachedReplications = totalCachedReplications + modelInput.numReplications
                } else {
                    // The cached solution doesn't have enough replications. We need to simulate more replications.
                    val requiredReps = modelInput.numReplications - numRepsInCache
                    // make the new request
                    val updatedModelInput = modelInput.instance(requiredReps)
                    // capture new request in the revised requests.
                    revisedModelInputs.add(updatedModelInput)
                    // part of the replications will be satisfied by the cache
                    totalCachedReplications = totalCachedReplications + numRepsInCache
                }
            } else {
                // keep the original unchanged
                revisedModelInputs.add(modelInput)
            }
        }
        return revisedModelInputs
    }

    /**
     * Evaluate directly via the simulation oracle (without accessing the cache).
     *
     * @param evaluationRequest  the requested evaluations (input, number of desired replications, and CRN requirements)
     * @return a map of the model inputs with their accompanying solution evaluations
     */
    private fun evaluateViaSimulation(
        evaluationRequest: EvaluationRequest
    ): Map<ModelInputs, Solution> {
        val modelInputs = evaluationRequest.modelInputs
        totalOracleEvaluations = totalOracleEvaluations + modelInputs.size
        totalOracleReplications = totalOracleReplications + modelInputs.totalReplications()
        // run the evaluations
        //TODO this is the long-running task
        val cases = simulator.simulate(evaluationRequest)
        val solutions: MutableMap<ModelInputs, Solution> = mutableMapOf()
        // Converts (EvaluationRequest, ResponseMap) pairs to (EvaluationRequest, Solution)
        for ((request, result) in cases) {
            solutions[request] = if (result.isFailure) {
                problemDefinition.badSolution()
            } else {
                createSolution(request, result.getOrNull()!!)
            }
        }
        EvaluatorIfc.logger.trace { "Requests simulated, resulting in ${solutions.size} solutions" }
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
        request: ModelInputs,
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
        request: ModelInputs,
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
            appendLine("totalEvaluations = $totalEvaluations")
            appendLine("totalOracleEvaluations = $totalOracleEvaluations")
            appendLine("totalCachedEvaluations = $totalCachedEvaluations")
            appendLine("totalRequestsReceived = $totalRequestsReceived")
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
        @Suppress("unused")
        fun filterToUniqueRequests(requests: List<ModelInputs>): List<ModelInputs> {
            val uniqueRequests = mutableMapOf<ModelInputs, ModelInputs>()

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
fun List<ModelInputs>.totalReplications(): Int {
    return sumOf { it.numReplications }
}