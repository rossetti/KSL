package ksl.simopt.solvers

fun interface SolutionQualityEvaluatorIfc {

    fun isStoppingCriteriaReached(solver: Solver): Boolean

}