package ksl.simopt.problem

import kotlin.math.pow

/**
 * Deprecated placeholder. This class was intended to implement the Penalty Function with
 * Memory (PFM) of Park and Kim (2015) —
 * Park, C., &amp; Kim, S. H. (2015). Penalty Function with Memory for Discrete Optimization via
 * Simulation with Stochastic Constraints. Operations Research, 63(5), 1195-1212 —
 * but it did not. It applied a polynomial penalty to the raw violation with a
 * `1/sqrt(sampleCount)` factor that is not part of PFM and that *weakened* the penalty as the
 * number of observations (the evidence of infeasibility) grew.
 *
 * That factor has been removed, so this class now behaves exactly as the corrected
 * [DynamicPolynomialPenalty] (the "naive penalty" of Park and Kim 2015): `sampleCount` is
 * ignored. It is retained only so existing references and serialized configurations keep
 * working. A faithful PFM — the standardized measure of violation plus the per-solution
 * appreciating/depreciating penalty sequence — is planned for a future release, at which point
 * this name may be reused for it.
 *
 * @param basePenalty scaling coefficient (M0); must be &gt; 0 and finite. Default 100.0.
 * @param iterationExponent power applied to the iteration counter; must be &gt;= 0 and finite. Default 1.0.
 * @param violationExponent power applied to the violation magnitude; must be &gt; 0 and finite. Default 1.0.
 */
@Deprecated(
    message = "Not the Park & Kim (2015) PFM: the 1/sqrt(sampleCount) factor has been removed and " +
        "this now behaves as DynamicPolynomialPenalty. A faithful PFM is planned for a future " +
        "release. Use DynamicPolynomialPenalty.",
    replaceWith = ReplaceWith("DynamicPolynomialPenalty(basePenalty, iterationExponent, violationExponent)")
)
class PenaltyFunctionWithMemory(
    val basePenalty: Double = 100.0,
    val iterationExponent: Double = 1.0,
    val violationExponent: Double = 1.0,
) : PenaltyFunctionIfc {

    init {
        require(basePenalty.isFinite()) { "The base penalty must be finite." }
        require(iterationExponent.isFinite()) { "The iteration exponent must be finite." }
        require(violationExponent.isFinite()) { "The violation exponent must be finite" }
        require(basePenalty > 0.0) { "basePenalty must be positive" }
        require(iterationExponent >= 0.0) { "iterationExponent must be non-negative" }
        require(violationExponent > 0.0) { "violationExponent must be positive" }
    }

    override fun penalty(violation: Double, iterationCounter: Int, sampleCount: Int): Double {
        if (violation <= 0.0) return 0.0
        // Neutralized: the former 1/sqrt(sampleCount) "memory" factor is intentionally removed
        // (not part of Park & Kim 2015); sampleCount is ignored. Identical to DynamicPolynomialPenalty.
        val p = basePenalty * iterationCounter.toDouble().pow(iterationExponent) *
            violation.pow(violationExponent)
        return minOf(p, Double.MAX_VALUE)
    }
}
