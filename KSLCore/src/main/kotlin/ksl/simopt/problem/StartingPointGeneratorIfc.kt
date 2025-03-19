package ksl.simopt.problem

import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc

fun interface StartingPointGeneratorIfc {

    fun generate(problemDefinition: ProblemDefinition): DoubleArray

}

class RandomStartingPointGenerator(
    val rnStream: RNStreamIfc
) : StartingPointGeneratorIfc, RNStreamControlIfc by rnStream {

    override fun generate(problemDefinition: ProblemDefinition): DoubleArray {
        TODO("Not implemented yet")
    }


}