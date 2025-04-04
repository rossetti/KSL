package ksl.simopt.evaluator

import kotlin.math.pow

fun interface PenaltyFunctionIfc {

    fun penalty(iterationCounter: Int): Double
}

object NaivePenaltyFunctionExponentTwo : PenaltyFunctionIfc {
    override fun penalty(iterationCounter: Int): Double {
        return iterationCounter.toDouble().pow(2.0)
    }
}

class NaivePenaltyFunction(val exponent: Double = 2.0) : PenaltyFunctionIfc {
    override fun penalty(iterationCounter: Int): Double {
        return iterationCounter.toDouble().pow(exponent)
    }
}