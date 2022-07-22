package ksl.utilities.random.rvariable

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistics
import kotlin.math.pow
import kotlin.math.sin

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

fun main() {
    var rv1 = ExponentialRV(10.0)
    var rv2 = ExponentialRV(20.0)
    // default is sum
    var rv = RVFunction(rv1, rv2)
    // divide them
    var rv3 = RVFunction(rv1, rv2, { f: Double, s: Double -> f / s })

//    print(rv.sample(100).statistics())

    println()

//    print(rv3.sample(100).statistics())

    var n = (NormalRV(10.0, 2.0) + ExponentialRV(100.0)) / NormalRV(1.0, 2.0)

//    print(n.sample(100).statistics())

    val rv4 = RVUFunction(rv1, { f: Double -> sin(f) })

//    print(rv4.sample(100).statistics())

    val x = rv1 + 3.0

    val y = 3.0 + rv1

    val z = 3.0 * rv1

//    print(z.sample(100).statistics())

    val w = sin(rv1)
//    print(w.sample(100).statistics())

    val p = rv1.pow(2.0)

//    print(p.sample(100).statistics())

    var v = ExponentialRV(10.0)
    val m = 2.0.pow(v)
//    val m = sin(v)
//    m.useStreamNumber(100)
    val sn = m.streamNumber
    println("using stream number = $sn")
    m.resetStartStream()
    print(m.sample(100).statistics())

    println()
    println()
//    v.useStreamNumber(100)
    v.useStreamNumber(sn)
    v.resetStartStream()
    val s = Statistic()
    for(i in 1..100){
        val y = 2.0.pow(v.value)
//        val y = sin(v.value)
        s.collect(y)
    }
    print(s)
}