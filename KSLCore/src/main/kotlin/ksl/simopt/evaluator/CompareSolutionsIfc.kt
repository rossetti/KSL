package ksl.simopt.evaluator

fun interface CompareSolutionsIfc {

    fun compare(first: Solution, second: Solution) : Int

}