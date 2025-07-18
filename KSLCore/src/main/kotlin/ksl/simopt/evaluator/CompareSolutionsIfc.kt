package ksl.simopt.evaluator

/**
 *  Can be supplied to specialize the comparison of solutions within solvers.
 */
fun interface CompareSolutionsIfc {

    fun compare(first: Solution, second: Solution) : Int

}

/**
 *  Compares solutions based on granular objective function values.
 */
object ObjFnGranularitySolutionComparator : CompareSolutionsIfc {
    override fun compare(first: Solution, second: Solution): Int {
        return first.granularObjFncValue.compareTo(second.granularObjFncValue)
    }
}

/**
 *  Compares solutions based on granular penalized objective function values.
 */
object PenalizedObjFnGranularitySolutionComparator : CompareSolutionsIfc {
    override fun compare(first: Solution, second: Solution): Int {
        return first.granularPenalizedObjFncValue.compareTo(second.granularPenalizedObjFncValue)
    }
}