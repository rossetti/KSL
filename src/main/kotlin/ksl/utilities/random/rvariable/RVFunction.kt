package ksl.utilities.random.rvariable

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.statistics

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
}

fun main() {
    var rv1 = ExponentialRV(10.0)
    var rv2 = ExponentialRV(20.0)
    var rv = RVFunction(rv1, rv2)
    var rv3 = RVFunction(rv1, rv2, { f: Double, s: Double -> f / s })

    print(rv.sample(100).statistics())

}