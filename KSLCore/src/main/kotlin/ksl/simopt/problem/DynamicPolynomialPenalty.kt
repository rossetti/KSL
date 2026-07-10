package ksl.simopt.problem

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
 * stochastic (response) constraints; the violation is supplied already computed.
 *
 * @param basePenalty The scaling coefficient (M_0). Default is 100.0. Must be > 0.
 * @param iterationExponent The power applied to the iteration counter. Default is 1.0.
 * @param violationExponent The power applied to the violation magnitude. Default is 1.0
 * (linear). Linear is preferred: squaring a sub-unit violation (such as a fill-rate gap)
 * collapses the penalty toward zero.
 */
class DynamicPolynomialPenalty(
    val basePenalty: Double = 100.0,
    val iterationExponent: Double = 1.0,
    val violationExponent: Double = 1.0,
) : PenaltyFunctionIfc {

    init {
        require(basePenalty.isFinite()) {"The base penalty must be finite."}
        require(iterationExponent.isFinite()) {"The iteration exponent must be finite."}
        require(violationExponent.isFinite()) {"The violation exponent must be finite"}
        require(basePenalty > 0.0) { "basePenalty must be positive, was $basePenalty" }
        require(violationExponent > 0.0) { "violationExponent must be positive, was $violationExponent" }
        require(iterationExponent >= 0.0) { "iterationExponent must be non-negative, was $iterationExponent" }
    }

    override fun penalty(violation: Double, iterationCounter: Int, sampleCount: Int): Double {
        if (violation <= 0.0) return 0.0

        // P(v, t) = (C * t^beta) * v^alpha
        val timeFactor = iterationCounter.toDouble().pow(iterationExponent)
        val violationFactor = violation.pow(violationExponent)

        val p = (basePenalty * timeFactor) * violationFactor

        return minOf(p, Double.MAX_VALUE)
    }

    companion object {

        var defaultPenaltyFunction: PenaltyFunctionIfc = DynamicPolynomialPenalty()
    }
}