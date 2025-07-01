package ksl.simopt.problem

fun interface ConstraintFunctionIfc {

    fun lhs(inputs: Map<String, Double>): Double

}