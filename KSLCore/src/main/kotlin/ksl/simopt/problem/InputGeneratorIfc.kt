package ksl.simopt.problem

fun interface InputGeneratorIfc {

    fun generate(problemDefinition: ProblemDefinition): DoubleArray

}