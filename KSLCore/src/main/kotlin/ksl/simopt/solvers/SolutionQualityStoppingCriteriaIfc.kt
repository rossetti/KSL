package ksl.simopt.solvers

import ksl.simopt.evaluator.Solution

fun interface SolutionQualityEvaluatorIfc {

    fun isStoppingCriteriaReached(solver: Solver): Boolean

}