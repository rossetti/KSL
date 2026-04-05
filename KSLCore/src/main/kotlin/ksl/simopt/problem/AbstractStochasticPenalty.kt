package ksl.simopt.problem

import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.Solution

/**
 * The foundational base class for all stochastic penalty functions applied to Response Constraints.
 * * This class abstracts away the complexity of extracting statistical estimates from the
 * Solution object and provides a suite of highly-optimized, zero-allocation mathematical
 * utilities. Concrete implementations should extract the [EstimatedResponse] exactly once
 * using [estimatedLHS] and pass it to the utilities to ensure O(1) performance and data consistency.
 *
 * @property constraint The specific ResponseConstraint this penalty function evaluates.
 */
abstract class AbstractStochasticPenalty(val constraint: ResponseConstraint) {

    // --- STATE EXTRACTION ---

    /**
     * Extracts the estimated response for this constraint's left-hand side from the Solution.
     * Executes in strict O(1) time by querying the Solution's primary map.
     *
     * @param solution The current iteration's solution object.
     * @return The specific EstimatedResponse tied to this constraint.
     * @throws IllegalStateException if the simulation oracle failed to provide the required response.
     */
    protected fun estimatedLHS(solution: Solution): EstimatedResponse {
        return solution.responseEstimatesMap[constraint.responseName]
            ?: error("Critical Failure: The estimated response '${constraint.responseName}' was not found in the Solution map.")
    }

    // --- MATHEMATICAL UTILITIES ---
    // These utilities accept the concrete EstimatedResponse rather than the Solution object.
    // This enforces a pattern where the concrete penalty class extracts the estimate ONCE
    // per iteration, guaranteeing both data lineage and maximum execution speed.

    /** * Computes the raw magnitude of the violation based on the sample average.
     * Returns 0.0 if the constraint is fully satisfied.
     */
    protected fun violation(est: EstimatedResponse): Double {
        return constraint.violation(est.average)
    }

    /** * Computes the slack (distance to the boundary) based on the sample average.
     * Positive if strictly feasible, 0.0 if active, negative if violated.
     */
    protected fun slack(est: EstimatedResponse): Double {
        return constraint.slack(est.average)
    }

    /** * Computes the mathematical difference (LHS - RHS) oriented for a less-than constraint.
     * Positive means violated, negative means feasible.
     */
    protected fun difference(est: EstimatedResponse): Double {
        return constraint.difference(est.average)
    }

    /** * Returns true if the sample average currently strictly satisfies the constraint.
     */
    protected open fun isFeasible(est: EstimatedResponse): Boolean {
        return violation(est) == 0.0
    }

    //TODO test for statistical feasibility, within tolerance, within target?

    // --- STATISTICAL UTILITIES ---

    /** * Returns the current sample count (N) for this simulation response.
     */
    protected fun sampleCount(est: EstimatedResponse): Int {
        return est.count.toInt()
    }

    /** * Returns the standard error of the estimated response.
     */
    protected fun standardError(est: EstimatedResponse): Double {
        return est.standardError
    }

    /** * Returns the 95% confidence interval half-width.
     */
    protected fun halfWidth(est: EstimatedResponse): Double {
        return est.halfWidth()
    }

    /**
     * Calculates the Signal-to-Noise Ratio of the violation (Violation / Standard Error).
     * This describes how many standard errors the violation is past the constraint boundary.
     * * - `< 1.0`: The violation is weak and could easily be stochastic noise.
     * - `> 3.0`: The violation is statistically strong; the solution is highly likely infeasible.
     */
    protected fun violationToNoiseRatio(est: EstimatedResponse): Double {
        val v = violation(est)
        if (v == 0.0) return 0.0

        val se = standardError(est)
        if (se == 0.0) return v // Fallback if variance is strictly zero to prevent division by zero

        return v / se
    }

    // --- CORE CONTRACT ---

    /**
     * Calculates the penalty value for the current solution.
     * * Advanced stochastic penalties should use the provided statistical utilities to
     * dampen penalties based on sample variance, preventing standard error from infinitely
     * penalizing valid boundary solutions.
     *
     * @param solution The current solution evaluated by the simulation oracle.
     * @param iterationCounter The current iteration or generation of the solver.
     * @return The calculated penalty value (should be 0.0 if fully feasible).
     */
    abstract fun calculatePenalty(solution: Solution, iterationCounter: Int): Double
}