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
 * A Monte Carlo black-box evaluator that can be optimized by the simulation-optimization solvers.
 *
 * Implementations own their replication mechanics. The evaluator passes the requested design point
 * and the full [ModelInputs] request, including [ModelInputs.numReplications]. The implementation
 * should perform the requested replication work and return a [ResponseMap] containing statistical
 * summaries for the objective response and any response-constraint responses.
 *
 * This mirrors the existing KSL model path: `SimulationProvider` performs model replications and
 * returns summarized responses; this interface performs non-[ksl.simulation.Model] replications and
 * returns the same summarized response representation.
 */
fun interface MonteCarloFunctionIfc {

    /**
     * Evaluates a Monte Carlo function at the supplied design point.
     *
     * @param x the design point ordered by the associated problem definition's input names
     * @param modelInputs the full evaluation request for this point, including requested replications
     * @return summarized objective and response estimates for this design point
     */
    fun evaluate(x: DoubleArray, modelInputs: ModelInputs): ResponseMap
}
