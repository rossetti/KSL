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

class RandomStartingPointGenerator(
    val rnStream: RNStreamIfc
) : StartingPointIfc, RNStreamControlIfc by rnStream {

    constructor(streamNum: Int) : this(KSLRandom.rnStream(streamNum))

    override fun startingPoint(
        problemDefinition: ProblemDefinition,
        roundToGranularity: Boolean
    ): Map<String, Double> {
        return problemDefinition.randomPoint(rnStream, roundToGranularity)
    }
}