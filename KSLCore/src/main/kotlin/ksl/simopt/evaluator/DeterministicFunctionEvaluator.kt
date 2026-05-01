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
        MonteCarloFunctionEvaluator.validateEvaluationRequest(problemDefinition, evaluationRequest)

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
                    else -> MonteCarloFunctionEvaluator.mergeSolutions(
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
        val x = MonteCarloFunctionEvaluator.requestToInputArray(problemDefinition, request)
        val functionEvaluation = function.evaluate(x)
        val responseMap = deterministicEvaluationToResponseMap(
            problemDefinition = problemDefinition,
            functionEvaluation = functionEvaluation,
            count = request.numReplications
        )
        return MonteCarloFunctionEvaluator.responseMapToSolution(
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
