package ksl.simopt.problem

/**
 *  A penalty function that is applied to the solution to the optimization problem.
 *  The penalty function is a function of the current solution and the current iteration
 *  count. A penalty function is added to the objective function to penalize infeasible
 *  solutions.
 */
fun interface PenaltyFunctionIfc {

    /**
     *  The penalty function.
     *  @param inputMap The current input map. This also contains a reference to the
     *  problem definition.
     *  @param responseAverages The average response values for each response variable required
     *  to compute the penality associated with the response constraints.
     *  @param iterationCounter The current iteration count. The iteration associated
     *  with the solver's process.
     */
    fun penalty(
        inputMap: InputMap,
        responseAverages: Map<String, Double>,
        iterationCounter: Int
    ): Double

}