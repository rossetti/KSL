package ksl.simopt.problem

import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

fun interface StartingPointIfc {

    fun startingPoint(
        problemDefinition: ProblemDefinition,
        roundToGranularity: Boolean
    ): Map<String, Double>

}
