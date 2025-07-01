package ksl.simopt.problem

fun interface StartingPointIfc {

    fun startingPoint(
        problemDefinition: ProblemDefinition,
    ): InputMap

}

