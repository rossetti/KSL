package ksl.utilities.random.rvariable

import ksl.utilities.random.rng.RNStreamIfc

class RVUFunction(
    theFirst: RVariableIfc,
    theTransform: ((f: Double) -> Double) = { f: Double -> f },
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : RVariable(stream, name) {

    private val first = theFirst.instance(rnStream)
    private val transform = theTransform

    override fun instance(stream: RNStreamIfc): RVariableIfc {
        return RVUFunction(first, transform, rnStream, name)
    }

    override fun generate(): Double {
        return transform(first.value)
    }
}