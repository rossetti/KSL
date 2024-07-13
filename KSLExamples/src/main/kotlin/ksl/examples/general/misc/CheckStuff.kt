package ksl.examples.general.misc

import ksl.utilities.distributions.Weibull
import ksl.utilities.io.StatisticReporter
import ksl.utilities.random.rvariable.*
import ksl.utilities.statistic.CachedHistogram
import ksl.utilities.statistic.Statistic
import org.jetbrains.letsPlot.commons.intern.math.ipow
import org.jetbrains.letsPlot.commons.testing.assertContentEquals

fun main(){
//    val twos = IntArray(10){ (2).ipow(it+3).toInt() }
//    println(twos.joinToString())
//    testPearson()
//    testWeibull()

//    val example = "House     Of The Dragon"
//    val withOutSpaces = example.replace("\\p{Zs}+".toRegex(), "_")
//    println(withOutSpaces)
 //   val lt1 = ConstantRV(10.0)
    val lt2 = ShiftedGeometricRV(probOfSuccess = 0.2, streamNum = 1)
    simulateDemandDuringLeadTime(1000, lt2)

}

fun chunky(){
    val r = 1..40
    val chunks: List<List<Int>> = r.chunked(39)
    println(chunks)

    println(1..39)
}

fun testPearson(){
    val rv = PearsonType5RV(shape=3.0, scale=8.0)
    val data = rv.sample(2000)
    val ch = CachedHistogram(data)
    val plot = ch.histogramPlot()
    plot.showInBrowser()
}

fun testWeibull(){
    val rv = WeibullRV(shape=3.0, scale=8.0)
    val data = rv.sample(2000)
    val  shape1 = Weibull.initialShapeEstimate(data)
    println("1 shape = $shape1")
    val  shape2 = Weibull.approximateInitialShapeEstimate(data)
    println("2 shape = $shape2")
//    val ch = CachedHistogram(data)
//    val plot = ch.histogramPlot()
//    plot.showInBrowser()

}

fun leadTimeDemand(leadTime: RVariableIfc, demand: RVariableIfc) : Double {
    var sum = 0.0
    val T = leadTime.value.toInt()
    for (i in 1..T){
        sum = sum + demand.value
    }
    return sum
}

fun simulateDemandDuringLeadTime(sampleSize: Int, leadTime: RVariableIfc){
    val stat = Statistic("LTD Stats")
    val probStat = Statistic("LTD >= 10")
    val d = DEmpiricalRV(doubleArrayOf(4.0, 5.0, 6.0, 7.0, 8.0),
        doubleArrayOf(0.1, 0.4, 0.75, 0.85, 1.0), 2)
    for(i in 1..sampleSize){
        val ltd = leadTimeDemand(leadTime, d)
        stat.collect(ltd)
        probStat.collect(ltd >= 65.0)
    }
    val r = StatisticReporter(mutableListOf(stat, probStat))
    println(r.halfWidthSummaryReport())
}