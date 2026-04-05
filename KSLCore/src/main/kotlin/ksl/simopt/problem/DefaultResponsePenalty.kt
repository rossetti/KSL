package ksl.simopt.problem

import ksl.simopt.evaluator.Solution
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The default stochastic penalty function applied to [ResponseConstraint]s.
 *
 * This penalty function features a "memory stabilizer" designed specifically for the noise
 * inherent in simulation optimization. Because a simulation evaluation with a small sample
 * size (low $N$) has high variance, we should not heavily penalize a solution that
 * might actually be feasible but suffered from initial noise.
 *
 * To mitigate this, the penalty is dampened by a factor of $1 / \sqrt{N}$. As the solver
 * invests more replications into a solution (increasing $N$), the stabilizer approaches 1.0,
 * allowing the full weight of the penalty to be applied only when statistical confidence is high.
 *
 * The mathematical formulation is:
 * `Penalty = [baseWeight * (iterationCounter ^ iterationExponent)] * (violation ^ violationExponent) * (1.0 / sqrt(N))`
 *
 * @param constraint The specific [ResponseConstraint] to which this penalty applies.
 * @property baseWeight The initial scaling factor for the penalty. Must be > 0.0. Default is 100.0.
 * @property iterationExponent Controls how rapidly the penalty grows over successive solver iterations.
 * A value of 1.0 represents linear growth. Must be >= 0.0. Default is 1.0.
 * @property violationExponent Controls how severely larger violations are punished relative to smaller ones.
 * A value of 2.0 (quadratic) is standard. Must be >= 0.0. Default is 2.0.
 */
class DefaultResponsePenalty(
    constraint: ResponseConstraint,
    val baseWeight: Double = 100.0,
    val iterationExponent: Double = 1.0,
    val violationExponent: Double = 2.0
) : AbstractStochasticPenalty(constraint) {

    init {
        require(baseWeight > 0.0) { "The baseWeight must be > 0. Provided: $baseWeight" }
        require(iterationExponent >= 0.0) { "The iterationExponent must be non-negative. Provided: $iterationExponent" }
        require(violationExponent >= 0.0) { "The violationExponent must be non-negative. Provided: $violationExponent" }
    }

    /**
     * Calculates the dynamic, variance-dampened penalty value for the current solution.
     *
     * @param solution The current solution evaluated by the simulation oracle.
     * @param iterationCounter The current iteration or generation of the solver.
     * @return The calculated penalty value, or 0.0 if the constraint's sample average is strictly feasible.
     */
    override fun calculatePenalty(solution: Solution, iterationCounter: Int): Double {
        // 1. Extract the specific EstimatedResponse in O(1) time
        val est = estimatedLHS(solution)

        // 2. Early exit if the sample average satisfies the constraint
        if (isFeasible(est)) return 0.0

        // 3. Extract the raw violation and sample size using the base class pure math wrappers
        val v = violation(est)
        val n = maxOf(1.0, sampleCount(est).toDouble())

        // 4. Calculate the memory dampening stabilizer
        // High N -> Stabilizer approaches 1.0 (Full penalty)
        // Low N  -> Stabilizer is smaller (Dampened penalty to account for noise)
        val memoryStabilizer = 1.0 / sqrt(n)

        // 5. Calculate the dynamic iteration multiplier
        // Use maxOf(1, iterationCounter) to prevent 0.0 multipliers on iteration 0
        val effectiveIteration = maxOf(1, iterationCounter).toDouble()
        val dynamicWeight = baseWeight * effectiveIteration.pow(iterationExponent)

        // 6. Return the combined stochastic penalty
        return dynamicWeight * v.pow(violationExponent) * memoryStabilizer
    }

    /**
     * Returns a formatted string representation of this penalty function's configuration.
     * This is highly useful for outputting the active ProblemDefinition configuration to logs or UI.
     */
    override fun toString(): String {
        return buildString {
            append("DefaultResponsePenalty(")
            append("baseWeight=$baseWeight, ")
            append("iterationExponent=$iterationExponent, ")
            append("violationExponent=$violationExponent")
            append(")")
        }
    }
}