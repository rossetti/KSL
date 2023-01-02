package ksl.examples.book.chapter7

import ksl.modeling.nhpp.PiecewiseConstantRateFunction
import ksl.modeling.nhpp.RateFunctionIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.UniformRV
import ksl.utilities.toStrings

fun main() {

    val d = doubleArrayOf(15.0, 20.0, 15.0)
    val ar = doubleArrayOf(1.0, 2.0, 1.0)
    val f = PiecewiseConstantRateFunction(d, ar)
    println("-----")
    println("intervals")
    println(f)
    val times = nhppThinning(f, 50.0)
    println(times.contentToString())
}

fun nhppThinning(rateFunc: RateFunctionIfc, maxTime: Double) : DoubleArray {
    val list = mutableListOf<Double>()
    val rv = ExponentialRV(1.0/rateFunc.maximumRate)
    val u = UniformRV(0.0, rateFunc.maximumRate)
    var t = 0.0
    while (t < maxTime){
        var s = t
        do{
            s = s + rv.value
        } while((s < maxTime) && (u.value > rateFunc.rate(s)))
        t = s
        if (t < maxTime){
            list.add(t)
        }
    }
    return list.toDoubleArray()
}