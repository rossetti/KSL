package ksl.simopt.evaluator

import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition

/**
 * Provides user-facing context for evaluating a Monte Carlo function at one design point.
 *
 * The context wraps the solver's [ModelInputs] request and exposes the same information
 * in forms that are convenient for black-box Monte Carlo functions:
 *
 * * [inputMap] for named decision-variable access
 * * [x] for array-based numerical functions, ordered by [ProblemDefinition.inputNames]
 * * [numReplications] for the requested number of Monte Carlo observations
 * * response-map factory methods for converting observations or estimates into solver-ready output
 *
 * This avoids requiring users to manually translate solver request objects into [ResponseMap]
 * instances inside every context-based Monte Carlo function implementation.
 *
 * @param problemDefinition the problem definition associated with the evaluator
 * @param modelInputs the solver request for a single design point
 */
class MonteCarloEvaluationContext(
    val problemDefinition: ProblemDefinition,
    val modelInputs: ModelInputs
) {

    init {
        require(modelInputs.modelIdentifier == problemDefinition.modelIdentifier) {
            "Model inputs must have the same model identifier as the problem definition."
        }
        require(modelInputs.inputs.keys == problemDefinition.inputNames.toSet()) {
            "Model inputs must contain exactly the problem definition input names."
        }
        require(problemDefinition.isInputRangeFeasible(modelInputs.inputs)) {
            "Model inputs must be feasible with respect to input ranges."
        }

        val expectedResponses = problemDefinition.allResponseNames.toSet()
        val requestedResponses = modelInputs.responseNames.ifEmpty { expectedResponses }
        require(requestedResponses == expectedResponses) {
            "Model inputs must request the objective and all problem response names."
        }
    }

    /**
     * The requested design point as a named input map.
     */
    val inputMap: InputMap = problemDefinition.toInputMap(modelInputs.inputs.toMutableMap())

    /**
     * The requested design point ordered according to [ProblemDefinition.inputNames].
     */
    val x: DoubleArray = problemDefinition.inputNames
        .map { name -> inputMap.getValue(name) }
        .toDoubleArray()

    /**
     * The number of Monte Carlo replications requested by the solver for this design point.
     */
    val numReplications: Int
        get() = modelInputs.numReplications

    /**
     * The objective response name for the problem.
     */
    val objectiveResponseName: String
        get() = problemDefinition.objFnResponseName

    /**
     * The non-objective response names for the problem.
     */
    val responseNames: List<String>
        get() = problemDefinition.responseNames

    /**
     * The objective response name followed by the non-objective response names.
     */
    val allResponseNames: List<String>
        get() = problemDefinition.allResponseNames

    /**
     * Creates a solver-ready [ResponseMap] from raw objective and response observations.
     *
     * The objective observations are named using [ProblemDefinition.objFnResponseName].
     * The response observations must contain exactly one entry for each response-constraint
     * response named by [ProblemDefinition.responseNames].
     *
     * @param objectiveObservations raw observations of the objective response
     * @param responseObservations raw observations of response-constraint responses
     * @return a response map containing estimated responses computed from the observations
     */
    fun responseMapFromObservations(
        objectiveObservations: DoubleArray,
        responseObservations: Map<String, DoubleArray> = emptyMap()
    ): ResponseMap {
        return ResponseMap.fromObservations(
            problemDefinition = problemDefinition,
            objectiveObservations = objectiveObservations,
            responseObservations = responseObservations
        )
    }

    /**
     * Creates a solver-ready [ResponseMap] from an objective estimate and optional response estimates.
     *
     * This is useful when a Monte Carlo experiment has already collected statistical summaries.
     *
     * @param objectiveEstimate the estimate for the objective response
     * @param responseEstimates estimates for response-constraint responses, keyed by response name
     * @return a response map containing the objective and response estimates
     */
    fun responseMapFromEstimates(
        objectiveEstimate: EstimatedResponseIfc,
        responseEstimates: Map<String, EstimatedResponseIfc> = emptyMap()
    ): ResponseMap {
        return ResponseMap.fromEstimates(
            problemDefinition = problemDefinition,
            objectiveEstimate = objectiveEstimate,
            responseEstimates = responseEstimates
        )
    }

    /**
     * Creates a solver-ready [ResponseMap] from a complete collection of estimates.
     *
     * The estimates must contain the objective response and every response-constraint response
     * named by the problem definition.
     *
     * @param estimates the estimates to place in the response map
     * @return a response map containing the supplied estimates
     */
    fun responseMapFromEstimates(
        estimates: Iterable<EstimatedResponseIfc>
    ): ResponseMap {
        return ResponseMap.fromEstimates(
            problemDefinition = problemDefinition,
            estimates = estimates
        )
    }
}
