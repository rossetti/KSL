package ksl.simopt.problem

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A penalty function adapted for Simulation Optimization based on the "Memory"
 * principles of Park and Kim (2015).
 * Park, C., & Kim, S. H. (2015). Penalty Function with Memory for Discrete Optimization via Simulation with
 * Stochastic Constraints. Operations Research, 63(5), 1195-1212.
 * This function scales the dynamic multiplier using the sample count (memory)
 * to prevent stochastic noise from infinitely penalizing boundary solutions.
 */
class PenaltyFunctionWithMemory(
    val basePenalty: Double = 100.0,
    val iterationExponent: Double = 1.0,
    val violationExponent: Double = 2.0
) : PenaltyFunctionIfc {

    init {
        require(basePenalty > 0.0) { "basePenalty must be positive" }
        require(iterationExponent >= 0.0) { "iterationExponent must be non-negative" }
        require(violationExponent > 0.0) { "violationExponent must be positive" }
    }

    override fun penalty(violation: Double, iterationCounter: Int, sampleCount: Int): Double {
        if (violation <= 0.0) return 0.0

        // 1. Calculate the dynamic iteration multiplier (lambda_k)
        val lambda_k = basePenalty * iterationCounter.toDouble().pow(iterationExponent)

        // 2. The sample average violation magnitude
        val v = violation.pow(violationExponent)

        // 3. The PFM Noise Dampener
        // Safeguard to prevent division by zero, though valid EstimatedResponses should have count >= 1
        val safeSampleCount = maxOf(1, sampleCount).toDouble()
        val memoryStabilizer = 1.0 / sqrt(safeSampleCount)

        // 4. Compute final stabilized penalty
        val p = lambda_k * v * memoryStabilizer

        return minOf(p, Double.MAX_VALUE)
    }

    companion object {
        var defaultPenaltyFunction: PenaltyFunctionIfc = PenaltyFunctionWithMemory()
    }
}