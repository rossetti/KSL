package ksl.simopt.problem

import ksl.simopt.evaluator.Solution
import kotlin.math.pow

/**
 * A dynamic polynomial penalty that scales with both the magnitude of the constraint
 * violation and the current iteration of the solver:
 *
 *     P(v, k) = basePenalty * k^iterationExponent * v^violationExponent
 *
 * With the default violationExponent = 1.0 this is the "naive penalty" of Park and Kim
 * (2015): an increasing sequence M_k = basePenalty * k^iterationExponent multiplying the
 * (raw) violation. It applies uniformly to deterministic (linear, functional) and
 * stochastic (response) constraints; the violation is obtained from the bound constraint.
 *
 * @param basePenalty The scaling coefficient (M_0). Default is 100.0. Must be > 0.
 * @param iterationExponent The power applied to the iteration counter. Default is 1.0.
 * @param violationExponent The power applied to the violation magnitude. Default is 1.0
 * (linear). Linear is preferred: squaring a sub-unit violation (such as a fill-rate gap)
 * collapses the penalty toward zero.
 * @param constraint The bound constraint, or null for an unbound default template.
 */
class DynamicPolynomialPenalty(
    val basePenalty: Double = 100.0,
    val iterationExponent: Double = 1.0,
    val violationExponent: Double = 1.0,
    constraint: PenalizableConstraint? = null,
) : PenaltyFunction(constraint) {

    init {
        require(basePenalty.isFinite()) {"The base penalty must be finite."}
        require(iterationExponent.isFinite()) {"The iteration exponent must be finite."}
        require(violationExponent.isFinite()) {"The violation exponent must be finite"}
        require(basePenalty > 0.0) { "basePenalty must be positive, was $basePenalty" }
        require(violationExponent > 0.0) { "violationExponent must be positive, was $violationExponent" }
        require(iterationExponent >= 0.0) { "iterationExponent must be non-negative, was $iterationExponent" }
    }

    override fun boundTo(c: PenalizableConstraint): PenaltyFunction =
        DynamicPolynomialPenalty(basePenalty, iterationExponent, violationExponent, c)

    override fun penalty(solution: Solution): Double {
        val violation = constraint!!.violation(solution)
        if (violation <= 0.0) return 0.0
        // P(v, k) = (M_0 * k^beta) * v^alpha, with k = the solution's evaluation number
        val timeFactor = solution.evaluationNumber.toDouble().pow(iterationExponent)
        val violationFactor = violation.pow(violationExponent)
        val p = (basePenalty * timeFactor) * violationFactor
        return minOf(p, Double.MAX_VALUE)
    }

    companion object {

        var defaultPenaltyFunction: PenaltyFunction = DynamicPolynomialPenalty()
    }
}
