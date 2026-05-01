package ksl.simopt.evaluator

import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition

/**
 * Evaluates optimization solutions by delegating to a user supplied Monte Carlo function.
 *
 * This class is a concrete [EvaluatorIfc] implementation for black-box stochastic functions
 * that are not represented as KSL [ksl.simulation.Model] instances. The supplied
 * [MonteCarloFunctionIfc] receives one design point at a time, along with the full
 * [ModelInputs] request, and is responsible for performing the requested replications and
 * returning a [ResponseMap] of statistical summaries.
 *
 * The evaluator handles the solver-facing responsibilities:
 *
 * * validating requests against the [problemDefinition]
 * * converting named input maps into ordered arrays
 * * converting [ResponseMap] instances into [Solution] instances
 * * maintaining evaluator counters
 * * optionally satisfying requests from a [SolutionCacheIfc]
 *
 * Common-random-number requests bypass solution-cache lookup, matching the policy in
 * [Evaluator]. Any actual random-number coordination for non-model Monte Carlo functions is
 * the responsibility of the supplied [function].
 *
 * @param problemDefinition the optimization problem definition associated with this evaluator
 * @param function the Monte Carlo black-box function used to produce response summaries
 * @param cache an optional solution cache
 */
class MonteCarloFunctionEvaluator @JvmOverloads constructor(
    val problemDefinition: ProblemDefinition,
    private val function: MonteCarloFunctionIfc,
    override val cache: SolutionCacheIfc? = null
) : EvaluatorIfc {

    override var totalEvaluatorCalls: Int = 0
        private set

    override var totalDesignPointsEvaluated: Int = 0
        private set

    override var totalOracleReplications: Int = 0
        private set

    override var totalCachedReplications: Int = 0
        private set

    /**
     * Resets all evaluator counters to zero.
     */
    @Suppress("unused")
    fun resetEvaluationCounts() {
        totalEvaluatorCalls = 0
        totalDesignPointsEvaluated = 0
        totalOracleReplications = 0
        totalCachedReplications = 0
    }

    override fun evaluate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Solution> {
        validateEvaluationRequest(problemDefinition, evaluationRequest)

        totalEvaluatorCalls++
        totalDesignPointsEvaluated += evaluationRequest.modelInputs.size

        if (evaluationRequest.crnOption || !evaluationRequest.cachingAllowed || cache == null) {
            return evaluateViaFunction(evaluationRequest)
        }

        return cacheBasedEvaluation(evaluationRequest)
    }

    private fun cacheBasedEvaluation(evaluationRequest: EvaluationRequest): Map<ModelInputs, Solution> {
        require(cache != null) { "The cache must not be null for cache based evaluation" }

        val cachedSolutions = cache.retrieveSolutions(evaluationRequest.modelInputs)
        if (cachedSolutions.isEmpty()) {
            val evaluations = evaluateViaFunction(evaluationRequest)
            putValidSolutionsInCache(evaluations)
            return evaluations
        }

        val requestsToEvaluate = reviseRequestReplications(cachedSolutions, evaluationRequest.modelInputs)
        if (requestsToEvaluate.isNotEmpty()) {
            val revisedEvaluationRequest = evaluationRequest.instance(requestsToEvaluate)
            val evaluatedSolutions = evaluateViaFunction(revisedEvaluationRequest)

            for ((request, evaluatedSolution) in evaluatedSolutions) {
                val cachedSolution = cachedSolutions[request]
                cachedSolutions[request] = when {
                    !evaluatedSolution.isValid -> evaluatedSolution
                    cachedSolution == null -> evaluatedSolution
                    else -> mergeSolutions(
                        problemDefinition = problemDefinition,
                        request = request,
                        firstSolution = cachedSolution,
                        secondSolution = evaluatedSolution,
                        evaluationNumber = totalEvaluatorCalls
                    )
                }
            }

            putValidSolutionsInCache(cachedSolutions)
        }

        return cachedSolutions
    }

    private fun reviseRequestReplications(
        cachedSolutions: MutableMap<ModelInputs, Solution>,
        modelInputs: List<ModelInputs>
    ): List<ModelInputs> {
        val revisedModelInputs = mutableListOf<ModelInputs>()

        for (modelInput in modelInputs) {
            val cachedSolution = cachedSolutions[modelInput]
            if (cachedSolution == null) {
                revisedModelInputs.add(modelInput)
                continue
            }

            val numRepsInCache = cachedSolution.count.toInt()
            if (numRepsInCache >= modelInput.numReplications) {
                totalCachedReplications += modelInput.numReplications
            } else {
                val requiredReps = modelInput.numReplications - numRepsInCache
                revisedModelInputs.add(modelInput.instance(requiredReps))
                totalCachedReplications += numRepsInCache
            }
        }

        return revisedModelInputs
    }

    private fun evaluateViaFunction(evaluationRequest: EvaluationRequest): Map<ModelInputs, Solution> {
        totalOracleReplications += evaluationRequest.modelInputs.totalReplications()

        val solutions = mutableMapOf<ModelInputs, Solution>()
        for (request in evaluationRequest.modelInputs) {
            solutions[request] = runCatching {
                val x = requestToInputArray(problemDefinition, request)
                val responseMap = function.evaluate(x, request)
                responseMapToSolution(problemDefinition, request, responseMap, totalEvaluatorCalls)
            }.getOrElse {
                problemDefinition.badSolution()
            }
        }
        return solutions
    }

    private fun putValidSolutionsInCache(solutions: Map<out ModelInputs, Solution>) {
        val cacheableSolutions = solutions.filter { (request, solution) ->
            solution.isValid && request.inputs == solution.inputMap
        }
        cache?.putAll(cacheableSolutions)
    }

    companion object {

        /**
         * Converts a [ModelInputs] request into an array ordered according to
         * [ProblemDefinition.inputNames].
         *
         * This is the canonical conversion used by function-based evaluators. It avoids relying
         * on the iteration order of [ModelInputs.inputs], which may not match the problem's
         * declared input order.
         *
         * @param problemDefinition the problem definition that supplies the input ordering
         * @param request the model-input request containing named input values
         * @return an array of input values ordered by [ProblemDefinition.inputNames]
         */
        @JvmStatic
        fun requestToInputArray(
            problemDefinition: ProblemDefinition,
            request: ModelInputs
        ): DoubleArray {
            return problemDefinition.inputNames
                .map { name -> request.inputs[name] ?: error("Missing input '$name'.") }
                .toDoubleArray()
        }

        /**
         * Validates that an [EvaluationRequest] can be handled by a function-based evaluator.
         *
         * Function evaluators require explicit values for all decision variables because there is
         * no backing KSL model from which default input settings can be read. The request must also
         * ask for the objective response and every response named by the problem definition. An empty
         * response-name set is interpreted as requesting all problem responses, consistent with
         * [ModelInputs].
         *
         * @param problemDefinition the problem definition associated with the evaluator
         * @param evaluationRequest the request to validate
         */
        @JvmStatic
        fun validateEvaluationRequest(
            problemDefinition: ProblemDefinition,
            evaluationRequest: EvaluationRequest
        ) {
            require(evaluationRequest.modelIdentifier == problemDefinition.modelIdentifier) {
                "Evaluation request model identifier must match the problem definition."
            }

            val expectedInputs = problemDefinition.inputNames.toSet()
            val expectedResponses = problemDefinition.allResponseNames.toSet()

            for (request in evaluationRequest.modelInputs) {
                require(request.modelIdentifier == problemDefinition.modelIdentifier) {
                    "Model input identifier must match the problem definition."
                }
                require(request.inputs.keys == expectedInputs) {
                    "Request inputs must exactly match the problem definition input names."
                }
                require(problemDefinition.isInputRangeFeasible(request.inputs)) {
                    "Request inputs must be feasible with respect to input ranges."
                }

                val requestedResponses = request.responseNames.ifEmpty { expectedResponses }
                require(requestedResponses == expectedResponses) {
                    "Function evaluators require the objective and all problem response names."
                }
            }
        }

        /**
         * Validates that a [ResponseMap] returned by a function evaluator is compatible with a
         * [ProblemDefinition].
         *
         * The response map must have the same model identifier as the problem definition and must
         * contain an [EstimatedResponse] for the objective response and every response named by the
         * problem definition.
         *
         * @param problemDefinition the problem definition associated with the evaluator
         * @param responseMap the response map to validate
         */
        @JvmStatic
        fun validateResponseMap(
            problemDefinition: ProblemDefinition,
            responseMap: ResponseMap
        ) {
            require(responseMap.modelIdentifier == problemDefinition.modelIdentifier) {
                "Response map model identifier must match the problem definition."
            }
            val expectedResponses = problemDefinition.allResponseNames.toSet()
            require(responseMap.keys == expectedResponses) {
                "Response map must contain exactly the objective and all problem response names."
            }
        }

        /**
         * Converts a function-produced [ResponseMap] into a [Solution] associated with the
         * supplied [ModelInputs] request.
         *
         * @param problemDefinition the problem definition associated with the evaluator
         * @param request the input request that produced the response map
         * @param responseMap the objective and response estimates produced by the function
         * @param evaluationNumber the evaluator call number to record on the solution
         * @return a [Solution] suitable for consumption by solvers
         */
        @JvmStatic
        fun responseMapToSolution(
            problemDefinition: ProblemDefinition,
            request: ModelInputs,
            responseMap: ResponseMap,
            evaluationNumber: Int
        ): Solution {
            validateResponseMap(problemDefinition, responseMap)
            val objectiveName = problemDefinition.objFnResponseName
            val objective = responseMap[objectiveName]
                ?: error("Response map did not contain objective response '$objectiveName'.")

            val responseEstimates = responseMap
                .filterKeys { it != objectiveName }
                .values
                .toList()

            return Solution(
                inputMap = InputMap(problemDefinition, request.inputs.toMutableMap()),
                estimatedObjFnc = objective,
                responseEstimates = responseEstimates,
                evaluationNumber = evaluationNumber
            )
        }

        /**
         * Merges two independent [Solution] estimates for the same request into one combined
         * solution.
         *
         * This is used when a cache can partially satisfy a request and the evaluator only needs
         * to run the additional replications. The statistical summaries from the cached and newly
         * evaluated solutions are pooled through [ResponseMap.mergeAll].
         *
         * @param problemDefinition the problem definition associated with the solutions
         * @param request the request associated with the merged solution
         * @param firstSolution the first solution estimate
         * @param secondSolution the second independent solution estimate
         * @param evaluationNumber the evaluator call number to record on the merged solution
         * @return the merged solution
         */
        @JvmStatic
        fun mergeSolutions(
            problemDefinition: ProblemDefinition,
            request: ModelInputs,
            firstSolution: Solution,
            secondSolution: Solution,
            evaluationNumber: Int
        ): Solution {
            require(firstSolution.inputMap == secondSolution.inputMap) {
                "The inputs must be the same in order to merge solutions."
            }
            val mergedResponseMap = firstSolution.toResponseMap()
            mergedResponseMap.mergeAll(secondSolution.toResponseMap())
            return responseMapToSolution(problemDefinition, request, mergedResponseMap, evaluationNumber)
        }
    }
}
