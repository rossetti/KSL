package ksl.simopt.problem

import ksl.simopt.evaluator.Solution

/**
 *  A penalty function that is applied to the solution to the optimization problem.
 *  The penalty function is a function of the current solution and the current iteration
 *  count. A penalty function is added to the objective function to penalize infeasible
 *  solutions.
 */
fun interface PenaltyFunctionIfc {

    /**
     *  The penalty function.
     *  @param solution The solution for which the penalty is being computed.
     *  @param iterationCounter The current iteration count. The iteration associated
     *  with the solver's process.
     */
    fun penalty(solution: Solution, iterationCounter: Int): Double

}