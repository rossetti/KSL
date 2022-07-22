package ksl.utilities.random.rvariable

import ksl.utilities.random.rng.RNStreamIfc

class RVFunction(
    theFirst: RVariableIfc,
    theSecond: RVariableIfc,
    theTransform: ((f: Double, s: Double) -> Double) = { f: Double, s: Double -> f + s },
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : RVariable(stream, name) {

    private val first = theFirst.instance(rnStream)
    private val second = theSecond.instance(rnStream)
    private val transform = theTransform

    override fun instance(stream: RNStreamIfc): RVariableIfc {
        return RVFunction(first, second, transform, rnStream, name)
    }

    override fun generate(): Double {
        return transform(first.value, second.value)
    }

    override fun useStreamNumber(streamNumber: Int) {
        super.useStreamNumber(streamNumber) // sets rnStream
        first.useStreamNumber(streamNumber)
        second.useStreamNumber(streamNumber)
    }
}

