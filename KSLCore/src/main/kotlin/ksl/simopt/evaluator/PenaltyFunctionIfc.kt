package ksl.simopt.evaluator

import kotlin.math.pow

fun interface PenaltyFunctionIfc {

    fun penalty(iterationCounter: Int): Double
}

/**
 *  A naive penalty function as describe in
 *  [Chuljin Park, Seong-Hee Kim (2015) Penalty Function with Memory for Discrete
 *  Optimization via Simulation with Stochastic Constraints. Operations Research 63(5):1195-1212]
 *  (https://doi.org/10.1287/opre.2015.1417)
 *  @param initialValue The initial value in the penalty function sequence. The default value is 1000.0
 *  @param exponent The exponent in the penalty function sequence. The default is 2.0.
 */
class NaivePenaltyFunction(
    val initialValue: Double = 1000.0,
    val exponent: Double = 2.0
) : PenaltyFunctionIfc {

    init {
        require(initialValue > 0.0) {"initialValue must be positive, was $initialValue"}
        require(exponent > 0.0) {"exponent must be positive, was $exponent"}
    }

    override fun penalty(iterationCounter: Int): Double {
        val p = initialValue * iterationCounter.toDouble().pow(exponent)
        return minOf(p, Double.MAX_VALUE)
    }

    companion object {

        var defaultPenaltyFunction = NaivePenaltyFunction()
    }
}