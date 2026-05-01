package ksl.simopt.evaluator

/**
 * Holds the deterministic values produced by evaluating a black-box function at one design point.
 *
 * The [objective] value must correspond to the objective response name in the associated
 * problem definition. The optional [responses] map supplies values for response constraints,
 * keyed by the response names in the problem definition.
 *
 * @param objective the deterministic objective-function value at the supplied point
 * @param responses optional deterministic response values needed for response constraints
 */
data class DeterministicFunctionEvaluation(
    val objective: Double,
    val responses: Map<String, Double> = emptyMap()
)

/**
 * A deterministic black-box function that can be optimized by the simulation-optimization solvers.
 *
 * Implementations receive the design point as a [DoubleArray] ordered according to
 * [ksl.simopt.problem.ProblemDefinition.inputNames]. They should return the objective value and,
 * when the problem has response constraints, any required deterministic response values.
 */
fun interface DeterministicFunctionIfc {

    /**
     * Evaluates the deterministic function at the supplied design point.
     *
     * @param x the design point ordered by the associated problem definition's input names
     * @return the deterministic objective and optional response values at [x]
     */
    fun evaluate(x: DoubleArray): DeterministicFunctionEvaluation
}

/**
 * Convenience interface for a deterministic scalar objective function of the form `f(x)`.
 *
 * This interface is intended for problems that do not have response constraints. Use
 * [DeterministicFunctionIfc] when the function must also report named response values.
 */
fun interface ObjectiveFunctionIfc {

    /**
     * Evaluates the scalar objective function at the supplied design point.
     *
     * @param x the design point ordered by the associated problem definition's input names
     * @return the deterministic objective-function value
     */
    fun evaluate(x: DoubleArray): Double
}

/**
 * A stochastic observation function for use with [SamplingFunctionEvaluator].
 *
 * Implementations produce one observed value for each response requested in [modelInputs].
 * The evaluator owns the repeated sampling loop and statistical summarization.
 */
fun interface ObservationFunctionIfc {

    /**
     * Produces one observation of the objective response and any response-constraint responses.
     *
     * The returned map must contain exactly the response names requested by [modelInputs].
     * Solver-created requests normally ask for the objective response name and all response
     * names in the associated [ksl.simopt.problem.ProblemDefinition].
     *
     * @param modelInputs the requested design point and response names
     * @return one observed value for each requested response
     */
    fun observe(modelInputs: ModelInputs): Map<String, Double>
}

/**
 * Convenience interface for stochastic objective-only observations.
 *
 * This interface is intended for problems without response constraints. Use
 * [ObservationFunctionIfc] when the observation must also report response values.
 */
fun interface ObjectiveObservationFunctionIfc {

    /**
     * Produces one observed objective value for the requested design point.
     *
     * @param modelInputs the requested design point
     * @return one observed objective value
     */
    fun observe(modelInputs: ModelInputs): Double
}
