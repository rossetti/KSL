package ksl.simopt.evaluator

import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.problem.ProblemDefinition

/**
 * Evaluates optimization solutions by delegating to a deterministic black-box function.
 *
 * This class is a concrete [EvaluatorIfc] implementation for ordinary deterministic
 * functions that are not represented as KSL [ksl.simulation.Model] instances. The supplied
 * [DeterministicFunctionIfc] is evaluated once per design point. If the solver requests
 * multiple replications for a point, those replications are interpreted as repeated identical
 * observations of the deterministic value.
 *
 * For an estimated response created from a deterministic value:
 *
 * * a requested count of 1 produces an undefined sample variance, represented as [Double.NaN]
 * * a requested count greater than 1 produces sample variance 0.0
 *
 * The evaluator handles the solver-facing responsibilities:
 *
 * * validating requests against the [problemDefinition]
 * * converting named input maps into ordered arrays
 * * converting deterministic objective and response values into [EstimatedResponse] instances
 * * maintaining evaluator counters
 * * optionally satisfying requests from a [SolutionCacheIfc]
 *
 * @param problemDefinition the optimization problem definition associated with this evaluator
 * @param function the deterministic black-box function used to produce objective and response values
 * @param cache an optional solution cache
 */
class DeterministicFunctionEvaluator @JvmOverloads constructor(
    val problemDefinition: ProblemDefinition,
    private val function: DeterministicFunctionIfc,
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
                evaluateRequest(request)
            }.getOrElse {
                problemDefinition.badSolution()
            }
        }
        return solutions
    }

    private fun evaluateRequest(request: ModelInputs): Solution {
        val x = requestToInputArray(problemDefinition, request)
        val functionEvaluation = function.evaluate(x)
        val responseMap = deterministicEvaluationToResponseMap(
            problemDefinition = problemDefinition,
            functionEvaluation = functionEvaluation,
            count = request.numReplications
        )
        return responseMapToSolution(
            problemDefinition = problemDefinition,
            request = request,
            responseMap = responseMap,
            evaluationNumber = totalEvaluatorCalls
        )
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
                inputMap = problemDefinition.toInputMap(request.inputs.toMutableMap()),
                estimatedObjFnc = objective,
                responseEstimates = responseEstimates,
                evaluationNumber = evaluationNumber
            )
        }

        /**
         * Merges two independent [Solution] estimates for the same request into one combined
         * solution.
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

        /**
         * Creates an [EstimatedResponse] for a deterministic function value.
         *
         * Multiple requested replications are interpreted as repeated identical observations.
         * Thus, when [count] is greater than one, the sample variance is exactly 0.0. When
         * [count] is one, the sample variance is undefined and is represented as [Double.NaN],
         * matching the constructor contract of [EstimatedResponse].
         *
         * @param name the response name
         * @param value the deterministic value of the response
         * @param count the requested number of observations to associate with the estimate
         * @return an estimated response representing the deterministic value
         */
        @JvmStatic
        fun deterministicEstimate(name: String, value: Double, count: Int): EstimatedResponse {
            require(count > 0) { "The count must be greater than zero." }
            return if (count == 1) {
                EstimatedResponse(name, value, Double.NaN, 1.0)
            } else {
                EstimatedResponse(name, value, 0.0, count.toDouble())
            }
        }

        /**
         * Converts a [DeterministicFunctionEvaluation] into a [ResponseMap] for the supplied
         * [ProblemDefinition].
         *
         * The resulting response map contains an estimate for the objective response and an
         * estimate for every response named by the problem definition. If the problem definition
         * contains response names, [functionEvaluation] must supply values for all of them.
         *
         * @param problemDefinition the problem definition associated with the deterministic function
         * @param functionEvaluation the raw deterministic function values
         * @param count the requested number of observations to associate with each estimate
         * @return a response map suitable for conversion to a [Solution]
         */
        @JvmStatic
        fun deterministicEvaluationToResponseMap(
            problemDefinition: ProblemDefinition,
            functionEvaluation: DeterministicFunctionEvaluation,
            count: Int
        ): ResponseMap {
            val responseMap = problemDefinition.emptyResponseMap()
            responseMap.add(
                deterministicEstimate(
                    name = problemDefinition.objFnResponseName,
                    value = functionEvaluation.objective,
                    count = count
                )
            )

            for (responseName in problemDefinition.responseNames) {
                val value = functionEvaluation.responses[responseName]
                    ?: error("Function evaluation did not include response '$responseName'.")
                responseMap.add(deterministicEstimate(responseName, value, count))
            }

            return responseMap
        }

        /**
         * Convenience factory for deterministic problems that only have a scalar objective.
         *
         * Problems with response constraints need a [DeterministicFunctionIfc] so the function
         * can report the named response values required by the problem definition.
         *
         * @param problemDefinition the problem definition associated with the objective function
         * @param objective the scalar objective function
         * @param cache an optional solution cache
         * @return a deterministic function evaluator for the scalar objective
         */
        @JvmStatic
        @JvmOverloads
        fun forObjective(
            problemDefinition: ProblemDefinition,
            objective: ObjectiveFunctionIfc,
            cache: SolutionCacheIfc? = null
        ): DeterministicFunctionEvaluator {
            require(problemDefinition.responseNames.isEmpty()) {
                "A scalar objective function cannot satisfy response constraints."
            }

            return DeterministicFunctionEvaluator(
                problemDefinition = problemDefinition,
                function = DeterministicFunctionIfc { x ->
                    DeterministicFunctionEvaluation(objective.evaluate(x))
                },
                cache = cache
            )
        }
    }
}
