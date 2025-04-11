package ksl.simopt.evaluator

fun interface PenaltyFunctionIfc {

    fun penalty(iterationCounter: Int): Double
}

