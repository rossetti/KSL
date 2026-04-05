package ksl.simopt.problem

import kotlin.math.pow
import ksl.simopt.evaluator.Solution

/**
 * A dynamic polynomial penalty function for deterministic constraints.
 *
 * This penalty function grows dynamically as the optimization solver progresses through
 * its iterations. By starting with a lower penalty and increasing it polynomially, it
 * allows the solver to freely explore the infeasible region during early iterations,
 * but strictly forces convergence toward the feasible region as the search concludes.
 *
 * The mathematical formulation is:
 * `Penalty = baseWeight * (iterationCounter ^ iterationExponent) * (normalizedViolation ^ violationExponent)`
 *
 * @param constraint The specific [ConstraintIfc] to which this penalty applies.
 * @property baseWeight The initial scaling factor for the penalty. Must be > 0.0. Default is 100.0.
 * @property iterationExponent Controls how rapidly the penalty grows over successive iterations.
 * A value of 1.0 represents linear growth. Must be >= 0.0. Default is 1.0.
 * @property violationExponent Controls how severely larger violations are punished relative to smaller ones.
 * A value of 2.0 (quadratic) is standard. Must be > 0.0. Default is 2.0.
 */
class DynamicPolynomialPenalty(
    constraint: ConstraintIfc,
    val baseWeight: Double = 100.0,
    val iterationExponent: Double = 1.0,
    val violationExponent: Double = 2.0
) : AbstractDeterministicPenalty(constraint) {

    init {
        require(baseWeight > 0.0) { "The baseWeight must be > 0. Provided: $baseWeight" }
        require(iterationExponent >= 0.0) { "The iterationExponent must be non-negative. Provided: $iterationExponent" }
        require(violationExponent > 0.0) { "The violationExponent must be > 0. Provided: $violationExponent" }
    }

    /**
     * Calculates the dynamic penalty value for the current solution.
     *
     * @param solution The current solution being evaluated.
     * @param iterationCounter The current iteration or generation of the solver.
     * @return The calculated penalty value, or 0.0 if the constraint is strictly feasible.
     */
    override fun calculatePenalty(solution: Solution, iterationCounter: Int): Double {
        // 1. Early exit if the solution satisfies the deterministic constraint
        if (isFeasible(solution)) return 0.0

        // 2. Extract the normalized violation in O(1) mathematical time using the base class utility.
        // Normalized violation ensures fairness across constraints of vastly different scales.
        val nv = normalizedViolation(solution)

        // 3. Calculate the dynamic multiplier based on the solver's progression
        // Use maxOf(1, iterationCounter) to prevent 0.0 multipliers on iteration 0
        val effectiveIteration = maxOf(1, iterationCounter).toDouble()
        val dynamicWeight = baseWeight * effectiveIteration.pow(iterationExponent)

        // 4. Return the total penalty
        return dynamicWeight * nv.pow(violationExponent)
    }

    /**
     * Returns a formatted string representation of this penalty function's configuration.
     * This is highly useful for outputting the active ProblemDefinition configuration to logs or UI.
     */
    override fun toString(): String {
        return buildString {
            append("DynamicPolynomialPenalty(")
            append("baseWeight=$baseWeight, ")
            append("iterationExponent=$iterationExponent, ")
            append("violationExponent=$violationExponent")
            append(")")
        }
    }
}
