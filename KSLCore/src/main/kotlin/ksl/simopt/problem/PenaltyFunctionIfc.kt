package ksl.simopt.problem

/**
 *  A penalty function that is applied to the violation of a constraint of an optimization problem.
 *  The penalty function is a function of the violation and the current iteration
 *  count. The penalty for the constraint is added to the objective function of the
 *  problem.
 */
fun interface PenaltyFunctionIfc {
    /**
     * @param violation The magnitude of the constraint violation.
     * @param iterationCounter The current solver iteration (k).
     * @param sampleCount The number of times this response has been sampled N(x).
     */
    fun penalty(violation: Double, iterationCounter: Int, sampleCount: Int): Double
}