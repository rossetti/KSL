package ksl.examples.general.lectures.week2

import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.statistic.CachedHistogram
import kotlin.math.sqrt

fun main(){
    val e = ExponentialRV(10.0)
    val ch = CachedHistogram()
    for(i in 1..1000){
        val x = e.value
        ch.collect(x)
    }
    ch.histogramPlot().showInBrowser()
}

fun example(){
    val stream = KSLRandom.defaultRNStream()
    val ch = CachedHistogram()
    for(i in 1..10000){
        val x = exampleF(stream.randU01())
        ch.collect(x)
    }
    println(ch)
    val chPlot = ch.histogramPlot()
    chPlot.showInBrowser()
}

fun exampleF(u: Double) : Double {
    require(u in 0.0..1.0){"U must be in (0,1)"}
    return 5.0*sqrt(u)
}