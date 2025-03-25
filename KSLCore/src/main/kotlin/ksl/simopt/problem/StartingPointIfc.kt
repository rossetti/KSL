package ksl.simopt.problem

import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc

fun interface StartingPointIfc {

    fun startingPoint(problemDefinition: ProblemDefinition): Map<String, Double>

}

class RandomStartingPointGenerator(
    val rnStream: RNStreamIfc
) : StartingPointIfc, RNStreamControlIfc by rnStream {

    override fun startingPoint(problemDefinition: ProblemDefinition): Map<String, Double> {
        TODO("Not implemented yet")
    }


}