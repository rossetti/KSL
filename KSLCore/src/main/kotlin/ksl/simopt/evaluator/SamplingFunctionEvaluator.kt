package ksl.simopt.evaluator

import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.statistic.Statistic

/**
 * Evaluates optimization solutions by repeatedly sampling a user-supplied observation function.
 *
 * This class is a concrete [EvaluatorIfc] implementation for stochastic black-box functions
 * that are not represented as KSL [ksl.simulation.Model] instances. The supplied
 * [ObservationFunctionIfc] produces one observation for a design point. This evaluator owns
 * the repeated sampling loop, statistical summarization, response-map construction, solution
 * construction, evaluator counters, and optional solution-cache behavior.
 *
 * The observation function receives [ModelInputs], which is the same request object produced by
 * the solvers. It contains the model identifier, requested number of replications, named input
 * values, and requested response names.
 *
 * @param problemDefinition the optimization problem definition associated with this evaluator
 * @param observationFunction the stochastic function that produces one observation per call
 * @param cache an optional solution cache
 */
class SamplingFunctionEvaluator @JvmOverloads constructor(
    val problemDefinition: ProblemDefinition,
    private val observationFunction: ObservationFunctionIfc,
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
            return evaluateViaSampling(evaluationRequest)
        }

        return cacheBasedEvaluation(evaluationRequest)
    }

    private fun cacheBasedEvaluation(evaluationRequest: EvaluationRequest): Map<ModelInputs, Solution> {
        require(cache != null) { "The cache must not be null for cache based evaluation" }

        val cachedSolutions = cache.retrieveSolutions(evaluationRequest.modelInputs)
        if (cachedSolutions.isEmpty()) {
            val evaluations = evaluateViaSampling(evaluationRequest)
            putValidSolutionsInCache(evaluations)
            return evaluations
        }

        val requestsToEvaluate = reviseRequestReplications(cachedSolutions, evaluationRequest.modelInputs)
        if (requestsToEvaluate.isNotEmpty()) {
            val revisedEvaluationRequest = evaluationRequest.instance(requestsToEvaluate)
            val evaluatedSolutions = evaluateViaSampling(revisedEvaluationRequest)

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

    private fun evaluateViaSampling(evaluationRequest: EvaluationRequest): Map<ModelInputs, Solution> {
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
        val responseNames = expectedResponseNames(problemDefinition, request)
        val observationRequest = if (request.responseNames.isEmpty()) {
            request.copy(responseNames = responseNames)
        } else {
            request
        }
        val statistics = responseNames.associateWith { Statistic(it) }

        for (replication in 1..request.numReplications) {
            val observation = observationFunction.observe(observationRequest)
            require(observation.keys == responseNames) {
                "Observation $replication must contain exactly the response names $responseNames, " +
                        "but found ${observation.keys}."
            }

            for (name in responseNames) {
                statistics.getValue(name).collect(observation.getValue(name))
            }
        }

        val responseMap = ResponseMap.fromEstimates(
            problemDefinition = problemDefinition,
            estimates = statistics.values
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
         * Convenience factory for stochastic problems that only have a scalar objective.
         *
         * Problems with response constraints need an [ObservationFunctionIfc] so the observation
         * can report the named response values required by the problem definition.
         *
         * @param problemDefinition the problem definition associated with the objective observation
         * @param objectiveObservationFunction the scalar objective observation function
         * @param cache an optional solution cache
         * @return a sampling function evaluator for the scalar objective
         */
        @JvmStatic
        @JvmOverloads
        fun forObjective(
            problemDefinition: ProblemDefinition,
            objectiveObservationFunction: ObjectiveObservationFunctionIfc,
            cache: SolutionCacheIfc? = null
        ): SamplingFunctionEvaluator {
            require(problemDefinition.responseNames.isEmpty()) {
                "An objective-only observation function cannot satisfy response constraints."
            }

            return SamplingFunctionEvaluator(
                problemDefinition = problemDefinition,
                observationFunction = ObservationFunctionIfc { modelInputs ->
                    mapOf(
                        problemDefinition.objFnResponseName to objectiveObservationFunction.observe(modelInputs)
                    )
                },
                cache = cache
            )
        }

        /**
         * Validates that an [EvaluationRequest] can be handled by a sampling function evaluator.
         *
         * Sampling function evaluators require explicit values for all decision variables because
         * there is no backing KSL model from which default input settings can be read. The request
         * must also ask for the objective response and every response named by the problem definition.
         * An empty response-name set is interpreted as requesting all problem responses, consistent
         * with [ModelInputs].
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
                    "Sampling function evaluators require the objective and all problem response names."
                }
            }
        }

        /**
         * Returns the response names that a sampling observation must provide for [request].
         *
         * @param problemDefinition the problem definition associated with the evaluator
         * @param request the model-input request
         * @return the requested response names, or all problem response names if the request is empty
         */
        @JvmStatic
        fun expectedResponseNames(
            problemDefinition: ProblemDefinition,
            request: ModelInputs
        ): Set<String> {
            return request.responseNames.ifEmpty { problemDefinition.allResponseNames.toSet() }
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
         * Converts a [ResponseMap] into a [Solution] associated with the supplied [ModelInputs] request.
         *
         * @param problemDefinition the problem definition associated with the evaluator
         * @param request the input request that produced the response map
         * @param responseMap the objective and response estimates produced by sampling
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
    }
}
