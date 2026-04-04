package ksl.simopt.problem

import ksl.simopt.evaluator.Solution
import kotlin.math.abs

/**
 * The foundational base class for all deterministic penalty functions applied to
 * [LinearConstraint] and [FunctionalConstraint] implementations.
 *
 * Deterministic constraints evaluate directly against the mathematical inputs of a solution,
 * without the statistical noise inherent to simulation responses. This base class abstracts
 * away the extraction of input data from the [Solution] object and provides a suite of
 * highly-optimized, zero-allocation mathematical utilities.
 *
 * Concrete implementations should use the provided utility methods (such as [violation],
 * [slack], and [normalizedViolation]) to ensure consistent evaluation and maximum execution
 * speed during the optimization loop.
 *
 * **Note:** This base class is strictly for deterministic constraints ([ConstraintIfc]).
 * For stochastic penalty functions that require memory dampening or variance tracking,
 * use `AbstractStochasticPenalty` combined with a `ResponseConstraint`.
 *
 * @property constraint The specific deterministic constraint this penalty function evaluates.
 */
abstract class AbstractDeterministicPenalty(val constraint: ConstraintIfc) {

    // --- UTILITY FUNCTIONS ---

    /**
     * Computes the raw magnitude of the constraint violation based on the solution's inputs.
     * Delegates to the underlying constraint's violation math, which automatically accounts
     * for the `inequalityFactor`.
     * * @param solution The current iteration's solution object.
     * @return A strictly positive Double representing the violation magnitude, or 0.0 if feasible.
     */
    protected fun violation(solution: Solution): Double {
        return constraint.violation(solution.inputMap)
    }

    /**
     * Computes the mathematical slack (distance to the boundary) based on the solution's inputs.
     * * @param solution The current iteration's solution object.
     * @return A positive value if strictly feasible, 0.0 if active, and a negative value if violated.
     */
    protected fun slack(solution: Solution): Double {
        return constraint.slack(solution.inputMap)
    }

    /**
     * The mathematical difference (LHS - RHS) oriented for a less-than constraint.
     * This is the exact inverse of slack. Positive means violated, negative means feasible.
     * * @param solution The current iteration's solution object.
     * @return The difference between LHS and RHS.
     */
    protected fun difference(solution: Solution): Double {
        return -slack(solution)
    }

    /**
     * Returns true if the solution perfectly satisfies this constraint.
     * * @param solution The current iteration's solution object.
     * @return True if feasible, false otherwise.
     */
    protected fun isFeasible(solution: Solution): Boolean {
        return constraint.isSatisfied(solution.inputMap)
    }

    /**
     * Computes the violation scaled relative to the right-hand side limit of the constraint.
     * This is highly useful for adaptive algorithms to penalize constraints of vastly different
     * magnitudes (e.g., RHS = 1,000,000 vs RHS = 0.5) fairly and proportionately.
     *
     * @param solution The current iteration's solution object.
     * @return The proportional violation magnitude.
     */
    protected fun normalizedViolation(solution: Solution): Double {
        val v = violation(solution)
        if (v == 0.0) return 0.0

        // Use maxOf(1.0) to prevent division by zero or massive inflation on tiny RHS values
        val scale = maxOf(1.0, abs(constraint.ltRHSValue))
        return v / scale
    }

    // --- CORE CONTRACT ---

    /**
     * Calculates the penalty value for the current solution.
     *
     * @param solution The current solution evaluated by the simulation oracle.
     * @param iterationCounter The current iteration or generation of the solver.
     * @return The calculated penalty value (should be 0.0 if fully feasible).
     */
    abstract fun calculatePenalty(solution: Solution, iterationCounter: Int): Double
}